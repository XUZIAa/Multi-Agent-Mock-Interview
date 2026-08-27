package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.core.Text;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 定点小数格式化与 Python 的 {@code f"{x:.Nf}"} 逐值比对。
 *
 * <p>为什么值得单独测：Java 的 {@code String.format("%.2f", …)} 是 HALF_UP，Python 是
 * round-half-even，0.125 会给出两个不同的答案。这些数字会进提示词（评分历史、质量分、
 * 语速），差一位就是给模型的另一个信号。
 */
class FixedFormatParityTest {

    @Test
    void fixedMatchesPythonFormat() throws Exception {
        JsonNode samples = Parity.load("fixed");
        Assumptions.assumeTrue(samples != null, "没有 fixed 基准文件");

        for (JsonNode s : samples) {
            double v = s.get("v").asDouble();
            assertEquals(s.get("s0").asText(), Text.fixed(v, 0), v + " 精度0");
            assertEquals(s.get("s1").asText(), Text.fixed(v, 1), v + " 精度1");
            assertEquals(s.get("s2").asText(), Text.fixed(v, 2), v + " 精度2");
        }
    }
}
