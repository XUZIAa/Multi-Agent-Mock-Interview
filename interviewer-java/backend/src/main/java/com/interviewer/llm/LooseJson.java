package com.interviewer.llm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewer.core.error.ProviderResponseException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 宽松结构化输出。对应 Python 版的 LooseModel。
 *
 * <p>模型对同一字段可能给字符串、对象或对象列表，键名也常换（skill 写成 requirement）。
 * 严格反序列化会直接抛异常，于是整份复盘缺一块、整个题库少几道——而且不报错，
 * 只在日志里留一行 warning。这一层就是为了不让那种事发生。
 *
 * <p>做法：绑定之前按目标类型把 JSON 树walk一遍，逐字段归一形态；键名差异交给
 * {@link JsonAlias}。只对 record 生效，且只在 agents 层显式调用——HTTP 与数据库
 * 路径仍走严格反序列化，那些数据是自己写的，不该容错。
 *
 * <p>约定：参与宽松解析的 record，每个规范构造器参数都要标 {@link JsonProperty}。
 * 没标时按驼峰转下划线兜底，但显式标注才不会在改名时悄悄错位。
 */
@Component
public class LooseJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper mapper;
    private final Map<Class<?>, List<Slot>> slots = new ConcurrentHashMap<>();

    public LooseJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 一个字段：可接受的键名（规范名优先，随后是别名）与目标类型。 */
    private record Slot(List<String> keys, JavaType type) {
    }

    /**
     * 归一后绑定。
     *
     * @throws ProviderResponseException 归一之后仍然绑不上，说明形态差得太远
     */
    public <T> T parse(JsonNode raw, Class<T> type) {
        JavaType target = mapper.getTypeFactory().constructType(type);
        JsonNode coerced = coerce(target, raw);
        try {
            return mapper.treeToValue(coerced, type);
        } catch (Exception e) {
            throw new ProviderResponseException(
                    type.getSimpleName() + " 绑定失败: " + e.getMessage(), "", e);
        }
    }

    /** 按目标类型归一一个节点。 */
    JsonNode coerce(JavaType type, JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return node;
        }
        Class<?> raw = type.getRawClass();

        // 布尔原样透传：Python 那边也是先判 bool 直接返回
        if (raw == boolean.class || raw == Boolean.class) {
            return node;
        }
        if (raw == String.class) {
            return NODES.textNode(Coerce.asText(node));
        }
        if (raw == int.class || raw == Integer.class) {
            return keepNull(raw, node) ? node : NODES.numberNode((int) Coerce.asNumber(node));
        }
        if (raw == long.class || raw == Long.class) {
            return keepNull(raw, node) ? node : NODES.numberNode((long) Coerce.asNumber(node));
        }
        if (raw == double.class || raw == Double.class
                || raw == float.class || raw == Float.class) {
            return keepNull(raw, node) ? node : NODES.numberNode(Coerce.asNumber(node));
        }
        // 枚举与 Map 原样透传：交给 Jackson 的 @JsonCreator 做宽松解析
        if (type.isEnumType() || type.isMapLikeType()) {
            return node;
        }
        if (type.isCollectionLikeType()) {
            return coerceList(type.getContentType(), node);
        }
        if (raw.isRecord()) {
            return coerceRecord(raw, node);
        }
        return node;
    }

    /**
     * 装箱类型的 null 必须留住。
     *
     * <p>把 {@code Integer chosenQuestionId} 的 null 变成 0，会被下游当成「真的选了
     * 编号 0 那道题」。原生类型没有这个歧义，照常归一。
     */
    private static boolean keepNull(Class<?> raw, JsonNode node) {
        return node.isNull() && !raw.isPrimitive();
    }

    private JsonNode coerceList(JavaType element, JsonNode node) {
        Class<?> raw = element == null ? Object.class : element.getRawClass();
        if (raw == String.class) {
            return Coerce.textArray(Coerce.asTextList(node));
        }
        if (raw.isRecord()) {
            ArrayNode objects = Coerce.asObjectList(node);
            ArrayNode out = NODES.arrayNode(objects.size());
            objects.forEach(item -> out.add(coerceRecord(raw, item)));
            return out;
        }
        return node;
    }

    /** 只改能对上字段的键，其余原样留着让 Jackson 忽略（等同 extra="ignore"）。 */
    private JsonNode coerceRecord(Class<?> type, JsonNode node) {
        if (!node.isObject()) {
            return node;
        }
        ObjectNode out = node.deepCopy();
        for (Slot slot : slotsOf(type)) {
            String hit = firstPresent(out, slot.keys());
            if (hit == null) {
                continue;
            }
            out.set(hit, coerce(slot.type(), out.get(hit)));
        }
        return out;
    }

    private static String firstPresent(ObjectNode node, List<String> keys) {
        for (String key : keys) {
            if (node.has(key)) {
                return key;
            }
        }
        return null;
    }

    private List<Slot> slotsOf(Class<?> type) {
        return slots.computeIfAbsent(type, this::describe);
    }

    private List<Slot> describe(Class<?> type) {
        Constructor<?> ctor = canonicalConstructor(type);
        Parameter[] params = ctor.getParameters();
        List<Slot> out = new ArrayList<>(params.length);
        for (Parameter param : params) {
            List<String> keys = new ArrayList<>();
            JsonProperty name = param.getAnnotation(JsonProperty.class);
            keys.add(name != null && !name.value().isEmpty()
                    ? name.value() : snakeCase(param.getName()));
            JsonAlias alias = param.getAnnotation(JsonAlias.class);
            if (alias != null) {
                Arrays.stream(alias.value()).filter(a -> !keys.contains(a)).forEach(keys::add);
            }
            out.add(new Slot(List.copyOf(keys),
                    mapper.getTypeFactory().constructType(param.getParameterizedType())));
        }
        return List.copyOf(out);
    }

    /** record 的规范构造器：参数类型与组件类型逐位相同的那一个。 */
    private static Constructor<?> canonicalConstructor(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] shape = Arrays.stream(components)
                .map(RecordComponent::getType).toArray(Class<?>[]::new);
        try {
            return type.getDeclaredConstructor(shape);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("找不到规范构造器: " + type.getName(), e);
        }
    }

    /** 与 Jackson 的 SNAKE_CASE 同规则，只在漏标 @JsonProperty 时兜底。 */
    private static String snakeCase(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 给测试与诊断用：看某个 record 认哪些键。 */
    Map<String, List<String>> keyMap(Class<?> type) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        RecordComponent[] components = type.getRecordComponents();
        List<Slot> found = slotsOf(type);
        for (int i = 0; i < found.size(); i++) {
            out.put(components[i].getName(), found.get(i).keys());
        }
        return out;
    }

    /** 归一但不绑定。校验归一化本身时用它，避免被绑定阶段的行为掩盖。 */
    JsonNode normalize(Class<?> type, JsonNode raw) {
        return coerce(mapper.getTypeFactory().constructType(type), raw);
    }
}
