package com.interviewer.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.config.AppSettings;
import com.interviewer.core.config.ConfigStore;
import com.interviewer.core.config.CredentialStore;
import com.interviewer.core.provider.ChatProvider;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.provider.RealtimeProvider;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.ProbeResult;
import com.interviewer.rpc.dto.ApiKeyBody;
import com.interviewer.rpc.dto.AudioDeviceOption;
import com.interviewer.rpc.dto.AudioDevices;
import com.interviewer.rpc.dto.Catalog;
import com.interviewer.rpc.dto.ModelOption;
import com.interviewer.rpc.dto.Ok;
import com.interviewer.rpc.dto.ProbeBody;
import com.interviewer.rpc.dto.ProbeOutcome;
import com.interviewer.rpc.dto.ProviderOption;
import com.interviewer.rpc.dto.RoleOption;
import com.interviewer.voice.RealtimeProbe;
import com.interviewer.voice.VoiceSidecar;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "config")
public class ConfigController {

    /** 探测用的读超时。比任何角色的正常超时都短：用户在等一个「能不能用」的答复。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final ConfigStore store;
    private final CredentialStore credentials;
    private final LlmRouter router;
    private final RealtimeProbe realtimeProbe;
    private final VoiceSidecar sidecar;
    private final ObjectMapper mapper;

    public ConfigController(ConfigStore store, CredentialStore credentials, LlmRouter router,
                            RealtimeProbe realtimeProbe, VoiceSidecar sidecar,
                            ObjectMapper mapper) {
        this.store = store;
        this.credentials = credentials;
        this.router = router;
        this.realtimeProbe = realtimeProbe;
        this.sidecar = sidecar;
        this.mapper = mapper;
    }

    /** 供应商、模型、音色与角色的可选项。前端据此渲染，不必抄一份常量。 */
    @GetMapping("/catalog")
    public Catalog catalog() {
        List<ProviderOption> chat = new ArrayList<>();
        for (ChatProvider provider : Providers.CHAT.values()) {
            chat.add(new ProviderOption(provider.key(), provider.label(), provider.key(),
                    provider.consoleUrl(), provider.defaultModel(), provider.models(), List.of()));
        }
        List<ProviderOption> realtime = new ArrayList<>();
        for (RealtimeProvider provider : Providers.REALTIME.values()) {
            List<ModelOption> voices = provider.voices().stream()
                    .map(voice -> new ModelOption(voice,
                            Providers.VOICE_LABELS.getOrDefault(voice, voice)))
                    .toList();
            realtime.add(new ProviderOption(provider.key(), provider.label(),
                    provider.credentialKey(), provider.consoleUrl(), provider.defaultModel(),
                    provider.models(), voices));
        }
        List<RoleOption> roles = Providers.ALL_ROLES.stream()
                .map(role -> new RoleOption(role, Providers.ROLE_LABELS.get(role)))
                .toList();
        return new Catalog(chat, realtime, roles);
    }

    /** 设备可能被别的程序占用，失败时返回空列表让界面回落到系统默认。 */
    @GetMapping("/audio/devices")
    public AudioDevices audioDevices() {
        JsonNode payload = sidecar.listDevices();
        if (payload == null) {
            return AudioDevices.EMPTY;
        }
        return new AudioDevices(devices(payload.path("inputs")), devices(payload.path("outputs")));
    }

    private List<AudioDeviceOption> devices(JsonNode array) {
        List<AudioDeviceOption> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(new AudioDeviceOption(item.path("name").asText(""),
                    item.path("index").asInt(-1)));
        }
        return out;
    }

    /**
     * 真连一次，把结果翻成人话。
     *
     * <p>没有这个，用户遇到问题分不清是自己的 Key 不对还是程序有毛病。
     */
    @PostMapping("/config/probe")
    public ProbeOutcome probe(@Valid @RequestBody ProbeBody body) {
        AppSettings settings = store.settings();
        if (body.realtime()) {
            RealtimeProvider provider = Providers.REALTIME.get(body.providerKey());
            if (provider == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "未知的实时供应商 " + body.providerKey());
            }
            String key = body.apiKey().isEmpty()
                    ? credentials.get(provider.credentialKey()) : body.apiKey();
            String model = body.model().isEmpty() ? provider.defaultModel() : body.model();
            return ProbeOutcome.of(realtimeProbe.probe(provider, key, model));
        }

        ChatProvider chat = Providers.CHAT.get(body.providerKey());
        if (chat == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "未知的文本供应商 " + body.providerKey());
        }
        // 自定义端点的地址不在目录里，落在用户填的那份配置上
        String baseUrl = chat.baseUrl().isEmpty()
                ? settings.getCustomChat().baseUrl() : chat.baseUrl();
        String key = body.apiKey().isEmpty() ? credentials.get(chat.key()) : body.apiKey();
        String model = body.model().isEmpty() ? chat.defaultModel() : body.model();
        ProbeResult result = router.probe(chat.key(), baseUrl, key, model, PROBE_TIMEOUT);
        return ProbeOutcome.of(result);
    }

    @GetMapping("/config")
    public AppSettings get() {
        return store.settings();
    }

    @PostMapping("/config")
    public Ok save(@Valid @RequestBody AppSettings settings) {
        store.save(settings);
        router.invalidate();
        return Ok.DONE;
    }

    /** 只回报有没有配，绝不回传密钥本体。 */
    @GetMapping("/config/keys/{provider_key}")
    public Map<String, Boolean> keyPresent(@PathVariable("provider_key") String providerKey) {
        return Map.of("present", credentials.present(providerKey));
    }

    @PostMapping("/config/keys")
    public Ok setKey(@Valid @RequestBody ApiKeyBody body) {
        credentials.set(body.providerKey(), body.apiKey());
        router.invalidate();
        return Ok.DONE;
    }
}
