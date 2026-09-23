package com.interviewer.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.error.ConfigException;
import com.interviewer.core.provider.ChatProvider;
import com.interviewer.core.provider.CustomEndpoint;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.provider.RoleBinding;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 全部明文配置。密钥不在这里——那些进系统凭据库。
 *
 * <p>这个对象同时是配置文件的内容、/config 接口的响应体、以及 /config 接口的
 * 请求体，所以每个字段都必须有默认值：前端漏传一项不能让整份配置崩掉。
 */
@Getter
@Setter
public class AppSettings {

    private Map<String, RoleBinding> roles = new LinkedHashMap<>(Providers.DEFAULT_ROLE_BINDINGS);
    private RealtimeSettings realtime = new RealtimeSettings();
    private AudioSettings audio = new AudioSettings();
    private OrchestrationSettings orchestration = new OrchestrationSettings();
    private FeatureSettings features = new FeatureSettings();
    private CustomEndpoint customChat = CustomEndpoint.empty();
    private String activeProfileName = "我";

    public void setRoles(Map<String, RoleBinding> value) {
        Map<String, RoleBinding> merged = new LinkedHashMap<>(Providers.DEFAULT_ROLE_BINDINGS);
        if (value != null) {
            value.forEach((role, binding) -> {
                if (binding != null) {
                    merged.put(role, binding);
                }
            });
        }
        this.roles = merged;
    }

    public void setRealtime(RealtimeSettings value) {
        this.realtime = value == null ? new RealtimeSettings() : value;
    }

    public void setAudio(AudioSettings value) {
        this.audio = value == null ? new AudioSettings() : value;
    }

    public void setOrchestration(OrchestrationSettings value) {
        this.orchestration = value == null ? new OrchestrationSettings() : value;
    }

    public void setFeatures(FeatureSettings value) {
        this.features = value == null ? new FeatureSettings() : value;
    }

    public void setCustomChat(CustomEndpoint value) {
        this.customChat = value == null ? CustomEndpoint.empty() : value;
    }

    @JsonIgnore
    public RoleBinding bindingFor(String role) {
        RoleBinding binding = roles.get(role);
        if (binding == null) {
            binding = Providers.DEFAULT_ROLE_BINDINGS.get(role);
        }
        if (binding == null) {
            throw new ConfigException("未知的模型角色: " + role);
        }
        return binding;
    }

    /**
     * 角色对应的接入点。
     *
     * <p>自定义供应商要把用户填的地址与模型清单覆盖进目录：custom 是本机端点
     * （地址留空就用默认的 Ollama 地址），openai_compat 是中转（地址必填，
     * 没有它连都不知道连哪）。
     */
    @JsonIgnore
    public ChatProvider chatCatalog(String role) {
        RoleBinding binding = bindingFor(role);
        ChatProvider catalog = Providers.chat(binding.provider());
        if (Providers.KEY_CUSTOM.equals(binding.provider())) {
            return catalog.override(customChat.baseUrl(), customChat.models());
        }
        if (Providers.KEY_OPENAI_COMPAT.equals(binding.provider())) {
            if (customChat.baseUrl().isEmpty()) {
                throw new ConfigException("openai_compat 未配置 base_url",
                        "请先在设置中填写自定义 OpenAI 兼容中转的接入地址");
            }
            return catalog.override(customChat.baseUrl(), customChat.models());
        }
        return catalog;
    }

    @JsonIgnore
    public String chatModel(String role) {
        RoleBinding binding = bindingFor(role);
        String model = (binding.model().isEmpty()
                ? chatCatalog(role).defaultModel() : binding.model()).strip();
        if (model.isEmpty()) {
            throw new ConfigException("角色 " + role + " 未绑定模型",
                    "请在「设置 → 模型」为该角色选择模型并保存；填写模型列表不会自动绑定角色。");
        }
        return model;
    }
}
