package com.interviewer.core.provider;

import java.util.List;

/**
 * OpenAI 兼容的文本模型接入点。换模型只改这里的一行。
 *
 * <p>国内主流平台都遵循这套协议，所以九个供应商共用同一个客户端实现，
 * 差别只在 baseUrl 与 model。
 */
public record ChatProvider(String key, String label, String baseUrl, String defaultModel,
                           List<String> models, String consoleUrl) {

    /** 自定义端点会把用户填的地址与模型清单覆盖进来。 */
    public ChatProvider override(String newBaseUrl, List<String> newModels) {
        return new ChatProvider(
                key, label,
                (newBaseUrl == null || newBaseUrl.isBlank()) ? baseUrl : newBaseUrl,
                defaultModel,
                (newModels == null || newModels.isEmpty()) ? models : List.copyOf(newModels),
                consoleUrl);
    }
}
