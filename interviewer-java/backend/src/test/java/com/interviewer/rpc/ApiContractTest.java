package com.interviewer.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.Parity;
import com.interviewer.core.event.AppEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 接口契约。
 *
 * <p>前端的类型全部由 OpenAPI 文档生成，所以这份文档就是契约本体：少一条路由、schema
 * 改了名、字段掉成驼峰，都会让界面在运行时才崩。基准是 Python 原版导出的 api-spec.json。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 原版由 FastAPI 生成，路径参数名是 snake_case，这里必须逐字一致。 */
    private static JsonNode reference;

    @Autowired
    private MockMvc mvc;

    private JsonNode spec;

    @BeforeAll
    static void loadReference() throws Exception {
        reference = Parity.load("api-spec");
    }

    private JsonNode spec() throws Exception {
        if (spec == null) {
            String body = mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            spec = MAPPER.readTree(body);
            // 落一份到 target，契约对不上时可以直接打开看差在哪
            Path dump = Path.of(System.getProperty("interviewer.dataRoot", "target"))
                    .resolve("api-docs.json");
            Files.createDirectories(dump.getParent());
            Files.writeString(dump, spec.toPrettyString(), StandardCharsets.UTF_8);
        }
        return spec;
    }

    @Test
    void everyRouteFromTheOriginalIsStillServed() throws Exception {
        assertNotNull(reference, "缺少 parity/api-spec.json 基准");
        JsonNode paths = spec().path("paths");
        List<String> missing = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = reference.path("paths").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode mine = paths.path(entry.getKey());
            if (mine.isMissingNode()) {
                missing.add(entry.getKey());
                continue;
            }
            entry.getValue().fieldNames().forEachRemaining(method -> {
                if (mine.path(method).isMissingNode()) {
                    missing.add(method.toUpperCase() + " " + entry.getKey());
                }
            });
        }
        assertTrue(missing.isEmpty(), "缺少原版的路由: " + missing);
    }

    @Test
    void schemaNamesMatchTheOriginal() throws Exception {
        assertNotNull(reference, "缺少 parity/api-spec.json 基准");
        // 原版的校验错误模型由 FastAPI 生成，Java 侧不存在对应物，不参与比对
        var ignored = java.util.Set.of("HTTPValidationError", "ValidationError");
        TreeSet<String> want = new TreeSet<>();
        reference.path("components").path("schemas").fieldNames()
                .forEachRemaining(name -> {
                    if (!ignored.contains(name)) {
                        want.add(name);
                    }
                });
        TreeSet<String> have = new TreeSet<>();
        spec().path("components").path("schemas").fieldNames().forEachRemaining(have::add);
        want.removeAll(have);
        assertTrue(want.isEmpty(), "缺少原版的 schema: " + want);
    }

    /**
     * 可空字段必须照旧可空。
     *
     * <p>Java 的 {@code Integer} 在文档里默认是非空，而前端拿它当 {@code number} 用：
     * 「还没评分」会被当成 0 分显示，「没有可收尾的会话」会被当成 0 号会话。
     */
    @Test
    void nullableFieldsStayNullable() throws Exception {
        assertNotNull(reference, "缺少 parity/api-spec.json 基准");
        JsonNode mine = spec().path("components").path("schemas");
        List<String> lost = new ArrayList<>();
        JsonNode want = reference.path("components").path("schemas");
        want.fields().forEachRemaining(schema -> {
            JsonNode props = schema.getValue().path("properties");
            props.fields().forEachRemaining(prop -> {
                if (!allowsNull(prop.getValue())) {
                    return;
                }
                JsonNode actual = mine.path(schema.getKey()).path("properties")
                        .path(prop.getKey());
                if (!actual.isMissingNode() && !allowsNull(actual)) {
                    lost.add(schema.getKey() + "." + prop.getKey());
                }
            });
        });
        assertTrue(lost.isEmpty(), "这些字段原本可空，现在变成必填了: " + lost);
    }

    /** 3.1 用 type 数组表达可空，3.0 用 nullable 标记，两种都要认。 */
    private static boolean allowsNull(JsonNode schema) {
        if (schema.path("nullable").asBoolean(false)) {
            return true;
        }
        JsonNode type = schema.path("type");
        // 3.1 里可空既可能是 type 数组，也可能是 anyOf 分支里单独一个 "null"
        if (type.isTextual() && "null".equals(type.asText())) {
            return true;
        }
        if (type.isArray()) {
            for (JsonNode item : type) {
                if ("null".equals(item.asText())) {
                    return true;
                }
            }
        }
        for (String combiner : new String[] {"anyOf", "oneOf"}) {
            for (JsonNode branch : schema.path(combiner)) {
                if (allowsNull(branch)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void payloadKeysStaySnakeCase() throws Exception {
        JsonNode schemas = spec().path("components").path("schemas");
        assertTrue(schemas.path("TranscriptCommitted").path("properties").has("turn_id"),
                "事件字段必须是 snake_case");
        assertTrue(schemas.path("StarProgress").path("properties").has("is_behavioral"),
                "布尔字段的 is 前缀不能被 Jackson 剥掉");
        assertTrue(schemas.path("PersonaContract").path("properties").has("is_builtin"),
                "人设契约的 is_builtin 不能变成 builtin");
        assertTrue(schemas.path("InterviewState").path("properties").has("company_tier"),
                "InterviewState 的字段名要与老库一致");
    }

    @Test
    void everyEventIsDeclaredForTheFrontend() throws Exception {
        JsonNode mapping = spec().path(OpenApiConfig.EVENTS_EXTENSION);
        assertTrue(mapping.isObject(), "缺少事件映射扩展字段");
        assertEquals(AppEvent.class.getPermittedSubclasses().length, mapping.size(),
                "事件映射必须覆盖全部事件类型");
        JsonNode schemas = spec().path("components").path("schemas");
        mapping.fields().forEachRemaining(entry -> assertTrue(
                schemas.has(entry.getValue().asText()),
                "事件 " + entry.getKey() + " 的载荷 schema 未注册"));
        assertTrue(mapping.has("task_progress"));
        assertTrue(mapping.has("live_score_updated"));
    }
}
