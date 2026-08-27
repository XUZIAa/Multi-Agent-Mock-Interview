package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.core.Text;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 通用数字格式与 Python 的 {@code f"{x:g}"} 逐值比对。
 *
 * <p>模型返回的数字字段常是字符串或对象，归一成文本时会走这条路径。Java 自带的 %g
 * 会把 3.0 输出成 3.00000，直接影响简历画像里的经验年限之类。
 */
class GFormatParityTest {

    @Test
    void gFormatMatchesPython() throws Exception {
        JsonNode samples = Parity.load("gformat");
        Assumptions.assumeTrue(samples != null, "没有 gformat 基准文件");

        for (JsonNode s : samples) {
            double v = Double.parseDouble(s.get("v").asText());
            assertEquals(s.get("g").asText(), Text.gFormat(v), "gFormat(" + s.get("v").asText() + ")");
        }
    }
}
