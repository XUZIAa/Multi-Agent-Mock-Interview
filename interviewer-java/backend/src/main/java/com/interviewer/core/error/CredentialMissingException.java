package com.interviewer.core.error;

public class CredentialMissingException extends ConfigException {

    public CredentialMissingException(String userMessage) {
        super("", userMessage);
    }

    @Override
    protected String defaultUserMessage() {
        return "尚未配置 API Key，请前往「设置」填写";
    }
}
