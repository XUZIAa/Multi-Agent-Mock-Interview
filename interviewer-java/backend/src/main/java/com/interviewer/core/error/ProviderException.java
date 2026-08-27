package com.interviewer.core.error;

/**
 * 模型服务调用失败。
 *
 * <p>{@code status} 必须留着：401 是 Key 不对、402 是没钱、404 是模型名错、
 * 429 是限流，翻成人话全靠它。null 表示压根没连上。
 */
public class ProviderException extends InterviewerException {

    private final Integer status;
    private final String provider;

    public ProviderException(String detail) {
        this(detail, null, "", null);
    }

    public ProviderException(String detail, Integer status, String provider) {
        this(detail, status, provider, null);
    }

    public ProviderException(String detail, Integer status, String provider, Throwable cause) {
        super(detail, null, cause);
        this.status = status;
        this.provider = provider == null ? "" : provider;
    }

    @Override
    protected String defaultUserMessage() {
        return "模型服务调用失败";
    }

    public Integer status() {
        return status;
    }

    public String provider() {
        return provider;
    }
}
