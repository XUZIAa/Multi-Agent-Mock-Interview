package com.interviewer.core.error;

/**
 * 本机限流拦截。
 *
 * <p>不是服务端 429，是自己拦的：编排层出 bug 导致连环调用时，先保住用户的额度。
 */
public class RateLimitedException extends ProviderException {

    public RateLimitedException(String detail, Throwable cause) {
        super(detail, null, "", cause);
    }

    @Override
    protected String defaultUserMessage() {
        return "模型调用过于频繁已被本机限流拦下，稍后会自动恢复";
    }
}
