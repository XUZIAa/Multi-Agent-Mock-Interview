package com.interviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 与 Python 原版逐字比对用的基准数据。
 *
 * <p>基准文件由原项目导出，放在 test/resources/parity 下。提示词与确定性规则是这套
 * 系统的核心，改动必须是有意的——比对是唯一能挡住无意漂移的手段。
 */
public final class Parity {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Parity() {
    }

    /** 读取基准。文件缺失返回 null，让测试自行决定跳过而不是炸掉构建。 */
    public static JsonNode load(String name) throws Exception {
        try (InputStream in = Parity.class.getResourceAsStream("/parity/" + name + ".json")) {
            if (in == null) {
                return null;
            }
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
