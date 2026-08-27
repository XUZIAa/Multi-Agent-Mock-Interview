package com.interviewer.data.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.error.InterviewerException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JSON 列的编解码。
 *
 * <p>刻意不做成 MyBatis TypeHandler：MyBatis-Plus 自带的那个用它自己的静态 ObjectMapper，
 * 命名策略与本应用的 SNAKE_CASE 不一致，payload 里的键名会变成驼峰，和老库对不上。
 * 显式转换换来的是零魔法与正确的键名。
 */
@Component
public class JsonCodec {

    private static final Logger log = LoggerFactory.getLogger(JsonCodec.class);

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new InterviewerException("JSON 序列化失败: " + e.getMessage(), null, e);
        }
    }

    /**
     * 读一个 JSON 列。
     *
     * <p>读不出来返回 null 而不是抛：一条坏记录不该让整个列表页打不开。调用方负责跳过。
     */
    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("解析 {} 失败，已跳过该记录: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析字符串数组失败: {}", e.getMessage());
            return List.of();
        }
    }
}
