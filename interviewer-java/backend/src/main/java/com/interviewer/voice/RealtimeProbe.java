package com.interviewer.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.Text;
import com.interviewer.core.provider.RealtimeProvider;
import com.interviewer.llm.ProbeResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 实时语音链路的握手探测。
 *
 * <p>只连上并等第一帧，不开麦克风、不发音频，所以不必惊动 Python sidecar——它的价值在
 * 音频设备与回声抑制，而这里一个字节的 PCM 都不涉及。
 *
 * <p>能区分「Key 无效」和「账号没开通该实时模型」，后者在 omni 系列上很常见。
 */
@Component
public class RealtimeProbe {

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper mapper;

    public RealtimeProbe(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ProbeResult probe(RealtimeProvider provider, String apiKey, String model) {
        if (!Text.notBlank(apiKey)) {
            return ProbeResult.fail("还没填 API Key");
        }
        if (!Text.notBlank(model)) {
            return ProbeResult.fail("还没选实时语音模型");
        }
        URI uri = URI.create(provider.wsUrl() + "?model="
                + URLEncoder.encode(model.strip(), StandardCharsets.UTF_8));
        CompletableFuture<String> firstFrame = new CompletableFuture<>();
        long started = System.nanoTime();
        WebSocket socket = null;
        try {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey.strip())
                    .connectTimeout(HANDSHAKE_TIMEOUT)
                    .buildAsync(uri, new FirstFrameListener(firstFrame))
                    .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            String raw = firstFrame.get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return interpret(raw, (System.nanoTime() - started) / 1_000_000);
        } catch (TimeoutException e) {
            return ProbeResult.fail("握手超时，检查网络或代理");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.fail("探测被中断");
        } catch (ExecutionException | CompletionException e) {
            return explain(e.getCause() == null ? e : e.getCause(), model);
        } catch (Exception e) {
            return explain(e, model);
        } finally {
            if (socket != null) {
                socket.abort();
            }
        }
    }

    private ProbeResult interpret(String raw, long cost) {
        JsonNode event;
        try {
            event = mapper.readTree(raw);
        } catch (Exception e) {
            return new ProbeResult(true, "已连通（首帧不是 JSON），握手 " + cost + " ms", cost);
        }
        String kind = event.path("type").asText("");
        if ("session.created".equals(kind)) {
            return new ProbeResult(true, "实时语音可用，握手 " + cost + " ms", cost);
        }
        if ("error".equals(kind)) {
            String detail = event.path("error").path("message").asText("");
            return ProbeResult.fail("握手被拒：" + Text.cut(
                    detail.isEmpty() ? "服务端返回错误" : detail, 120));
        }
        return new ProbeResult(true, "已连通（首帧 " + (kind.isEmpty() ? "未知" : kind)
                + "），握手 " + cost + " ms", cost);
    }

    private static ProbeResult explain(Throwable error, String model) {
        if (error instanceof WebSocketHandshakeException handshake) {
            int code = handshake.getResponse().statusCode();
            return switch (code) {
                case 401, 403 -> ProbeResult.fail("API Key 无效，或账号未开通该实时语音模型");
                case 404 -> ProbeResult.fail("实时模型不存在：" + model);
                case 429 -> ProbeResult.fail("请求过于频繁，或额度已用尽");
                default -> ProbeResult.fail("握手失败 HTTP " + code);
            };
        }
        if (error instanceof HttpTimeoutException || error instanceof TimeoutException) {
            return ProbeResult.fail("握手超时，检查网络或代理");
        }
        if (error instanceof IOException) {
            return ProbeResult.fail("连不上实时语音服务："
                    + Text.cut(Text.safe(error.getMessage()), 120));
        }
        return ProbeResult.fail(error.getClass().getSimpleName() + ": "
                + Text.cut(Text.safe(error.getMessage()), 120));
    }

    /** 首帧就是全部所需：拿到一帧完整文本立刻完成，之后的内容与结论无关。 */
    private static final class FirstFrameListener implements WebSocket.Listener {

        private final CompletableFuture<String> target;
        private final StringBuilder buffer = new StringBuilder();

        private FirstFrameListener(CompletableFuture<String> target) {
            this.target = target;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                target.complete(buffer.toString());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            target.completeExceptionally(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            target.completeExceptionally(new IOException(
                    "服务端关闭连接 " + statusCode + " " + Text.safe(reason)));
            return null;
        }
    }
}
