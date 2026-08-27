package com.interviewer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.Text;
import com.interviewer.core.config.AppSettings;
import com.interviewer.core.config.ConfigStore;
import com.interviewer.core.config.CredentialStore;
import com.interviewer.core.error.ProviderException;
import com.interviewer.core.provider.ChatProvider;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.provider.RealtimeProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 按角色分发模型客户端。同一 (供应商, 模型, 超时) 复用同一个连接池。
 *
 * <p>四个角色可以各绑不同模型，用来平衡成本与质量：导演每轮都要跑、要快要便宜；
 * 复盘一场只跑一次、可以用贵的。
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /** 状态码到人话的映射。没有这层，用户分不清是自己的 Key 不对还是程序有毛病。 */
    private static final Map<Integer, String> STATUS_HINT = Map.of(
            400, "请求被拒绝，通常是模型名不被该平台接受",
            401, "API Key 无效或已失效",
            402, "账户余额不足",
            403, "该 Key 没有此模型的访问权限，可能需要先开通",
            404, "模型不存在，或接口地址不对",
            429, "请求过于频繁，或额度已用尽");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ConfigStore store;
    private final CredentialStore credentials;
    private final ObjectMapper mapper;
    private final LooseJson loose;
    private final LlmSettings settings;
    private final LlmRateLimiter limiter;
    private final Map<String, LlmClient> clients = new ConcurrentHashMap<>();

    public LlmRouter(ConfigStore store, CredentialStore credentials, ObjectMapper mapper,
                     LooseJson loose, LlmSettings settings, LlmRateLimiter limiter) {
        this.store = store;
        this.credentials = credentials;
        this.mapper = mapper;
        this.loose = loose;
        this.settings = settings;
        this.limiter = limiter;
    }

    public LlmClient client(String role) {
        AppSettings app = store.settings();
        ChatProvider catalog = app.chatCatalog(role);
        String model = app.chatModel(role);
        Duration timeout = settings.timeoutOf(role);
        String key = catalog.key() + "|" + model + "|" + timeout.toMillis();

        return clients.computeIfAbsent(key, k -> {
            String apiKey = credentials.require(catalog.key());
            LlmClient created = new LlmClient(role, catalog.key(), model,
                    buildModel(catalog.key(), catalog.baseUrl(), apiKey, model, timeout),
                    mapper, loose, limiter);
            log.info("创建模型客户端 role={} provider={} model={}", role, catalog.key(), model);
            return created;
        });
    }

    /**
     * 预建客户端与 TLS 连接。
     *
     * <p>首次调用要建连接池、做 TLS 握手，这些开销压在语音链路启动之后会和音频抢线程，
     * 听感就是开场几秒一卡一卡。放在还没有音频的准备阶段做，等的是同一份时间但无感。
     */
    public void warm(String... roles) {
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (String role : roles) {
            tasks.add(CompletableFuture.runAsync(() -> {
                try {
                    client(role).complete(List.of(new UserMessage("hi")), 0.0, 1, false);
                } catch (Exception e) {
                    // 预热失败不影响后续真实调用，真出问题时那边会报准确的错
                    log.debug("预热 role={} 未成功: {}", role, e.toString());
                }
            }));
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
    }

    /** 配置变更后必须清空：baseUrl、model、Key 都可能换了。 */
    public void invalidate() {
        clients.clear();
        log.info("模型客户端缓存已清空");
    }

    /** 返回缺少 API Key 的供应商，界面用它做启动前检查。 */
    public List<String> missingCredentials() {
        AppSettings app = store.settings();
        List<String> missing = new ArrayList<>();
        for (String role : Providers.ALL_ROLES) {
            String key = app.chatCatalog(role).key();
            if (!credentials.present(key) && !missing.contains(key)) {
                missing.add(key);
            }
        }
        RealtimeProvider realtime = app.getRealtime().catalog();
        String voiceKey = realtime.credentialKey();
        if (!credentials.present(voiceKey) && !missing.contains(voiceKey)) {
            missing.add(voiceKey);
        }
        return missing;
    }

    /**
     * 用一次最小请求探测 Key 与模型能否真正跑通。
     *
     * <p>不走缓存也不走限流：用户可能填的是还没保存的新 Key，而探测本身就是一次调用，
     * 不该被限流规则挡住。
     */
    public ProbeResult probe(String providerKey, String baseUrl, String apiKey, String model,
                             Duration timeout) {
        if (!Text.notBlank(apiKey)) {
            return ProbeResult.fail("还没填 API Key");
        }
        if (!Text.notBlank(model)) {
            return ProbeResult.fail("还没选模型");
        }
        LlmClient probe = new LlmClient(Providers.ROLE_ASSIST, providerKey, model.strip(),
                buildModel(providerKey, baseUrl, apiKey.strip(), model.strip(), timeout),
                mapper, loose, null);
        long started = System.nanoTime();
        try {
            probe.complete(List.of(new UserMessage("hi")), 0.0, 8, false);
            long cost = (System.nanoTime() - started) / 1_000_000;
            return new ProbeResult(true, "连通正常，往返 " + cost + " ms", cost);
        } catch (ProviderException e) {
            return explain(e);
        } catch (Exception e) {
            // 探测不该把异常抛给界面
            return ProbeResult.fail(e.getClass().getSimpleName() + ": "
                    + Text.cut(Text.safe(e.getMessage()), 120));
        }
    }

    private static ProbeResult explain(ProviderException e) {
        String body = Text.safe(e.detail()).strip().replace("\n", " ");
        if (e.status() == null) {
            String tail = body.isEmpty() ? "" : "：" + Text.cut(body, 110);
            return ProbeResult.fail("连不上服务器，检查网络或代理" + tail);
        }
        int status = e.status();
        String hint = STATUS_HINT.get(status);
        if (hint == null) {
            hint = status >= 500 ? "服务端错误 " + status + "，稍后再试" : "请求失败 " + status;
        }
        // 400/404 要带上服务端原话，否则不知道到底哪里不对
        if ((status == 400 || status == 404) && !body.isEmpty()) {
            return ProbeResult.fail(hint + "｜" + Text.cut(body, 110));
        }
        return ProbeResult.fail(hint);
    }

    private OpenAiChatModel buildModel(String providerKey, String baseUrl, String apiKey,
                                       String model, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(timeout);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(trimSlash(baseUrl))
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .responseErrorHandler(new ProviderErrorHandler(providerKey))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                // 不重试：Python 版同样只在结构化解析失败时由应用层重问一次，
                // 传输层静默重试会让「限流」这类错误被放大成多次扣费
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    private static String trimSlash(String url) {
        String text = Text.safe(url).strip();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }
}
