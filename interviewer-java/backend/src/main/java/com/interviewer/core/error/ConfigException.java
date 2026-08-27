package com.interviewer.core.error;

public class ConfigException extends InterviewerException {

    public ConfigException(String detail) {
        super(detail);
    }

    public ConfigException(String detail, String userMessage) {
        super(detail, userMessage);
    }

    public ConfigException(String detail, String userMessage, Throwable cause) {
        super(detail, userMessage, cause);
    }

    @Override
    protected String defaultUserMessage() {
        return "配置不完整或不合法";
    }
}
