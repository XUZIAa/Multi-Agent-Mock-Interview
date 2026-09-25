package com.interviewer.core.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个文本角色绑定的模型。四个角色可分别指定，以平衡成本与质量。
 */
public record RoleBinding(String provider, String model) {

    @JsonCreator
    public RoleBinding(@JsonProperty("provider") String provider,
                       @JsonProperty("model") String model) {
        this.provider = (provider == null || provider.isBlank())
                ? Providers.KEY_DEEPSEEK : provider.strip();
        this.model = model == null ? "" : model.strip();
    }

    /** 没显式指定模型时用供应商的默认模型。 */
    public String resolvedModel() {
        return model.isEmpty() ? Providers.chat(provider).defaultModel() : model;
    }
}
