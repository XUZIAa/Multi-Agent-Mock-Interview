package com.interviewer.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词资源的读取。
 *
 * <p>归一化 CRLF 并去掉恰好一个尾换行：源文件是 LF 且无尾换行，但 git 的换行转换或
 * 编辑器保存都可能悄悄改掉，而提示词末尾多一个换行会影响模型对结尾指令的接续。
 */
public final class PromptResource {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptResource() {
    }

    public static String load(String name) {
        return CACHE.computeIfAbsent(name, PromptResource::read);
    }

    private static String read(String name) {
        String path = "/prompts/" + name + ".txt";
        try (InputStream in = PromptResource.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("提示词资源缺失: " + path);
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            return raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        } catch (IOException e) {
            throw new UncheckedIOException("提示词读取失败: " + path, e);
        }
    }
}
