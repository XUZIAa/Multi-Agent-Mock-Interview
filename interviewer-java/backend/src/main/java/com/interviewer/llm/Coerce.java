package com.interviewer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewer.core.Text;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 形态归一。
 *
 * <p>同一个字段，模型可能给字符串、给对象、也可能给对象列表（教育经历尤其常见），
 * schema 声明得再准也拦不住。这几个函数把任意形态压成目标形态，是整套结构化输出
 * 能长期跑住的原因——失效的后果不是报错，是复盘偶尔缺板块、题库偶尔少几道。
 */
final class Coerce {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final String DEFAULT_SEP = " · ";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Coerce() {
    }

    static String asText(JsonNode value) {
        return asText(value, DEFAULT_SEP);
    }

    /** 压成一行文本。分隔符只用于对象内部拼接，数组固定用全角分号。 */
    static String asText(JsonNode value, String sep) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText().strip();
        }
        // 布尔必须排在数字前：Python 里 bool 是 int 的子类，那边也是先判 bool
        if (value.isBoolean()) {
            return "";
        }
        if (value.isNumber()) {
            return Text.gFormat(value.asDouble());
        }
        if (value.isObject()) {
            List<String> parts = new ArrayList<>();
            for (Iterator<JsonNode> it = value.elements(); it.hasNext(); ) {
                JsonNode item = it.next();
                // 跳过嵌套容器，避免把课程清单之类整段拼进来
                if (item.isObject() || item.isArray()) {
                    continue;
                }
                String text = asText(item, sep);
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
            return String.join(sep, parts);
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : value) {
                String text = asText(item, sep);
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
            return String.join("；", parts);
        }
        return value.asText().strip();
    }

    /** 压成字符串列表，去空但保持顺序。 */
    static List<String> asTextList(JsonNode value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isNull() || value.isMissingNode()) {
            return out;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String text = asText(item);
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
            return out;
        }
        String text = asText(value);
        if (!text.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    /** 取出数字。「3 年经验」这类也能拿到 3。 */
    static double asNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return 0.0;
        }
        if (value.isBoolean()) {
            return 0.0;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            Matcher m = NUMBER.matcher(value.asText());
            return m.find() ? Double.parseDouble(m.group()) : 0.0;
        }
        if (value.isArray()) {
            // 取第一个非零，与 Python 的 truthy 判断一致
            for (JsonNode item : value) {
                double found = asNumber(item);
                if (found != 0.0) {
                    return found;
                }
            }
        }
        return 0.0;
    }

    /** 归一成对象数组。字符串元素折成 {"name": ...}，靠 name 别名接回目标字段。 */
    static ArrayNode asObjectList(JsonNode value) {
        ArrayNode out = NODES.arrayNode();
        if (value == null || value.isNull() || value.isMissingNode()) {
            return out;
        }
        if (value.isObject()) {
            out.add(value);
            return out;
        }
        if (value.isTextual()) {
            String text = value.asText().strip();
            if (!text.isEmpty()) {
                out.add(named(text));
            }
            return out;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isObject()) {
                    out.add(item);
                    continue;
                }
                String text = asText(item);
                if (!text.isEmpty()) {
                    out.add(named(text));
                }
            }
        }
        return out;
    }

    private static ObjectNode named(String text) {
        ObjectNode node = NODES.objectNode();
        node.put("name", text);
        return node;
    }

    static ArrayNode textArray(List<String> values) {
        ArrayNode out = NODES.arrayNode();
        values.forEach(out::add);
        return out;
    }
}
