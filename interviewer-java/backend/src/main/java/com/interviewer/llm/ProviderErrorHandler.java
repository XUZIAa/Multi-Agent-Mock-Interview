package com.interviewer.llm;

import com.interviewer.core.Text;
import com.interviewer.core.error.ProviderException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * 把 HTTP 错误原样翻成 {@link ProviderException}，带上精确状态码与服务端原话。
 *
 * <p>Spring AI 默认会把 4xx/5xx 包成 NonTransientAiException / TransientAiException，
 * 状态码只留在消息文本里。而 401 是 Key 不对、402 是没钱、404 是模型名错——翻成人话
 * 全靠这个数字，所以自己接管。
 */
class ProviderErrorHandler implements ResponseErrorHandler {

    private final String providerKey;

    ProviderErrorHandler(String providerKey) {
        this.providerKey = providerKey;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body = "";
        try (var in = response.getBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 读不到正文不影响判断，状态码本身已经够用
        }
        throw new ProviderException(Text.cut(body, 500), status, providerKey);
    }
}
