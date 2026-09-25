package com.interviewer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.Text;
import com.interviewer.core.error.ProviderResponseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型正文里强行抠出 JSON。
 *
 * <p>即使开了 response_format，模型也常给带围栏或带前后缀的输出；被截断的长 JSON 更常见。
 * 所以先剥围栏、再整体解析、最后从第一个括号起逐步收缩右边界试解析。
 */
public final class JsonExtract {

    private static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    private JsonExtract() {
    }

    public static JsonNode parse(ObjectMapper mapper, String text) {
        String raw = Text.safe(text).strip();
        Matcher fence = FENCE.matcher(raw);
        if (fence.find()) {
            raw = fence.group(1).strip();
        }
        JsonNode whole = tryRead(mapper, raw);
        if (whole != null) {
            return whole;
        }

        int start = firstBracket(raw);
        if (start < 0) {
            throw new ProviderResponseException("响应中没有 JSON: " + Text.cut(text, 200));
        }
        // 从最长开始往回收：被截断的输出往前找最后一个能闭合的位置
        for (int end = raw.length(); end > start; end--) {
            char last = raw.charAt(end - 1);
            if (last != '}' && last != ']') {
                continue;
            }
            JsonNode chunk = tryRead(mapper, raw.substring(start, end));
            if (chunk != null) {
                return chunk;
            }
        }
        throw new ProviderResponseException("JSON 解析失败: " + Text.cut(text, 200));
    }

    private static int firstBracket(String raw) {
        int brace = raw.indexOf('{');
        int bracket = raw.indexOf('[');
        if (brace < 0) {
            return bracket;
        }
        if (bracket < 0) {
            return brace;
        }
        return Math.min(brace, bracket);
    }

    private static JsonNode tryRead(ObjectMapper mapper, String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
