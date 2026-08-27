package com.interviewer.core.error;

public class RealtimeClosedException extends RealtimeException {

    public RealtimeClosedException(String detail) {
        super(detail);
    }

    @Override
    protected String defaultUserMessage() {
        return "实时语音连接已断开";
    }
}
