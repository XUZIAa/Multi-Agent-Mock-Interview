package com.interviewer.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.Parity;
import com.interviewer.core.error.ProviderResponseException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * JSON 抽取与 Python 的 extract_json 逐例比对。
 *
 * <p>覆盖围栏剥离、前后缀文字、多对象取第一个，以及两类失败：没有括号、有括号但解析不出。
 *
 * <p>基准里有一例是被截断的 JSON，Python 同样失败——「从右往左收缩」只能救尾部多余文字，
 * 救不了真被切断的结构。这是原版的实际行为，照搬。
 */
class JsonExtractParityTest {

    @Test
    void extractMatchesPythonBaseline() throws Exception {
        JsonNode samples = Parity.load("extract");
        Assumptions.assumeTrue(samples != null, "没有 extract 基准文件");

        ObjectMapper mapper = new ObjectMapper();
        for (JsonNode sample : samples) {
            String in = sample.get("in").asText();
            String tag = "输入 " + mapper.valueToTree(in);

            if (sample.get("ok").asBoolean()) {
                assertEquals(sample.get("json"), JsonExtract.parse(mapper, in), tag);
                continue;
            }
            ProviderResponseException thrown = assertThrows(ProviderResponseException.class,
                    () -> JsonExtract.parse(mapper, in), tag + " 应当抛错");
            assertEquals(sample.get("msg").asText(), thrown.detail(), tag + " 错误消息");
        }
    }
}
