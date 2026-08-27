package com.interviewer.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.Parity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 形态归一与 Python 的 coerce 模块逐值比对。
 *
 * <p>基准的输入直接写成 JSON，正好对应模型输出经解析后的形态。这一层失效不会报错，
 * 只会让复盘偶尔缺板块、题库偶尔少几道，所以必须靠比对锁住。
 */
class CoerceParityTest {

    @Test
    void primitivesMatchPythonBaseline() throws Exception {
        JsonNode samples = Parity.load("coerce");
        Assumptions.assumeTrue(samples != null, "没有 coerce 基准文件");

        for (JsonNode s : samples) {
            JsonNode in = s.get("in");
            String tag = "输入 " + in;

            assertEquals(s.get("text").asText(), Coerce.asText(in), tag + " asText");
            assertEquals(s.get("text_sep").asText(), Coerce.asText(in, "|"), tag + " asText(sep=|)");
            assertEquals(s.get("num").asDouble(), Coerce.asNumber(in), 1e-12, tag + " asNumber");
            assertEquals(texts(s.get("list")), Coerce.asTextList(in), tag + " asTextList");
            assertEquals(s.get("objs"), Coerce.asObjectList(in), tag + " asObjectList");
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
