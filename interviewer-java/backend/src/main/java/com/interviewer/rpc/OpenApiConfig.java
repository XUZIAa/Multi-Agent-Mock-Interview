package com.interviewer.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.event.AppEvent;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档。它不是给人看的，是前端类型的唯一真相源。
 *
 * <p>WebSocket 事件的载荷也一并塞进 components：少一个前端就瞎一块，而前端手写这些字段
 * 迟早和后端错位。事件名到 schema 名的映射写在扩展字段里，生成器据此产出 EventMap。
 */
// 没有 Web 容器就没有 springdoc，这整块也无从谈起（纯持久层测试就跑在这种上下文里）
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OpenApiConfig {

    /** 扩展字段名。生成器只认这一个键，不去猜哪些 schema 是事件。 */
    static final String EVENTS_EXTENSION = "x-interviewer-events";

    /**
     * 让文档里的字段名与真实 JSON 一致。
     *
     * <p>springdoc 默认用自己造的 ObjectMapper 解析类型，不认应用的 SNAKE_CASE 策略，文档里
     * 会落成 turnId 而运行时发出去的是 turn_id——前端类型据此生成，字段全对不上。
     *
     * <p>传副本：swagger 的 ModelResolver 会往 mapper 里注册自己的模块，别污染运行时那一份。
     *
     * <p>{@code enumsAsRef} 让枚举成为独立的命名 schema。默认是内联展开，那样前端拿不到
     * {@code CompanyTier} 这类类型名，只能到处重复写字面量联合。
     *
     * <p>解析模式跟着文档版本走（见 application.yml 里的 springdoc.api-docs.version）：两边
     * 对不上时可空标记会在序列化阶段被丢掉。
     */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper,
                                       SpringDocConfigProperties properties) {
        ModelResolver.enumsAsRef = true;
        return new ModelResolver(objectMapper.copy())
                .openapi31(properties.isOpenapi31());
    }

    @Bean
    public OpenAPI interviewerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI 模拟面试 本机接口")
                .version("1")
                .description("桌面前端与后端之间的本机通道。仅监听回环地址，需 token 认证。"));
    }

    /**
     * 补回引用型字段的可空标记。
     *
     * <p>OpenAPI 3.0 不允许 {@code $ref} 带兄弟键，所以枚举字段上的
     * {@code @Schema(nullable = true)} 会被整个抹掉。包一层 {@code allOf} 是规范里表达
     * 「可空的引用」的唯一写法。
     *
     * <p>不逐个字段特判：这里按注解统一处理，以后新增可空枚举字段不必再来改这段。
     */
    @Bean
    public PropertyCustomizer nullableReferences() {
        return (property, type) -> {
            if (property == null || property.get$ref() == null || !declaredNullable(type)) {
                return property;
            }
            return new Schema<>()
                    .nullable(true)
                    .addAllOfItem(new Schema<>().$ref(property.get$ref()));
        };
    }

    private static boolean declaredNullable(AnnotatedType type) {
        Annotation[] annotations = type.getCtxAnnotations();
        if (annotations == null) {
            return false;
        }
        for (Annotation annotation : annotations) {
            if (annotation instanceof io.swagger.v3.oas.annotations.media.Schema schema
                    && schema.nullable()) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public OpenApiCustomizer eventSchemas() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            // 3.0 与 3.1 各有一份 ModelConverters 单例，取错那份会绕过上面注册的
            // ModelResolver，字段名落回驼峰
            ModelConverters converters = ModelConverters.getInstance(
                    openApi.getSpecVersion() == SpecVersion.V31);
            Map<String, String> mapping = new LinkedHashMap<>();
            for (Class<?> type : AppEvent.class.getPermittedSubclasses()) {
                @SuppressWarnings("unchecked")
                Class<? extends AppEvent> event = (Class<? extends AppEvent>) type;
                mapping.put(AppEvent.nameOf(event), type.getSimpleName());
                register(converters, components, type);
            }
            openApi.addExtension(EVENTS_EXTENSION, mapping);
        };
    }

    private static void register(ModelConverters converters, Components components,
                                Class<?> type) {
        ResolvedSchema resolved = converters.readAllAsResolvedSchema(type);
        if (resolved == null) {
            throw new IllegalStateException("无法解析事件载荷: " + type.getName());
        }
        if (resolved.referencedSchemas != null) {
            resolved.referencedSchemas.forEach((name, schema) -> putIfAbsent(components, name,
                    schema));
        }
        if (resolved.schema != null) {
            putIfAbsent(components, type.getSimpleName(), resolved.schema);
        }
    }

    /** 接口响应里已出现过的类型不覆盖：那份带着 springdoc 的完整加工结果。 */
    private static void putIfAbsent(Components components, String name, Schema<?> schema) {
        Map<String, Schema> existing = components.getSchemas();
        if (existing == null || !existing.containsKey(name)) {
            components.addSchemas(name, schema);
        }
    }
}
