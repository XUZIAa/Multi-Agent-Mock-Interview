package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.domain.persona.BuiltinPersonas;
import com.interviewer.domain.persona.PersonaContract;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 人设编译结果与 Python 原版逐字比对。
 *
 * <p>这些文本是整套人格维持机制的输入，一个字的漂移就会改变模型行为。
 */
class PersonaParityTest {

    @Test
    void blocksMatchPythonBaseline() throws Exception {
        JsonNode expected = Parity.load("persona");
        Assumptions.assumeTrue(expected != null, "没有 persona 基准文件");

        Map<String, PersonaContract> actual = new LinkedHashMap<>();
        BuiltinPersonas.all().forEach(p -> actual.put(p.getName(), p));
        assertEquals(expected.size(), actual.size(), "内置人设数量不一致");

        expected.fieldNames().forEachRemaining(name -> {
            PersonaContract p = actual.get(name);
            assertNotNull(p, "缺少内置人设: " + name);
            JsonNode want = expected.get(name);
            assertEquals(want.get("identity").asText(), p.identityBlock(), name + " identity_block");
            assertEquals(want.get("style").asText(), p.styleBlock(), name + " style_block");
            assertEquals(want.get("rules").asText(), p.rulesBlock(), name + " rules_block");
            assertEquals(want.get("opening").asText(), p.opening(), name + " opening");
            assertEquals(want.get("thr").asDouble(), p.interruptThresholdSeconds(42.0), 1e-6,
                    name + " interrupt_threshold_seconds");
        });
    }
}
