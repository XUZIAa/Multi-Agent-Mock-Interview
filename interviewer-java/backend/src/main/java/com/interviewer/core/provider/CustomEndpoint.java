package com.interviewer.core.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 自定义端点的用户填写值。
 *
 * <p>对 custom 与 openai_compat 两个供应商都生效——前者是本机 Ollama 之类，
 * 后者是各种中转，两边都需要用户自己给地址和模型清单。
 */
public record CustomEndpoint(String baseUrl, List<String> models) {

    @JsonCreator
    public CustomEndpoint(@JsonProperty("base_url") String baseUrl,
                          @JsonProperty("models") List<String> models) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.models = models == null ? List.of() : models.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    public static CustomEndpoint empty() {
        return new CustomEndpoint("", List.of());
    }
}
