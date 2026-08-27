package com.interviewer.core.error;

public class ProviderResponseException extends ProviderException {

    public ProviderResponseException(String detail) {
        super(detail);
    }

    public ProviderResponseException(String detail, String provider) {
        super(detail, null, provider);
    }

    public ProviderResponseException(String detail, String provider, Throwable cause) {
        super(detail, null, provider, cause);
    }

    @Override
    protected String defaultUserMessage() {
        return "模型返回内容无法解析";
    }
}
