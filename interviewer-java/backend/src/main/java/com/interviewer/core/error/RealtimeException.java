package com.interviewer.core.error;

public class RealtimeException extends InterviewerException {

    public RealtimeException(String detail) {
        super(detail);
    }

    public RealtimeException(String detail, Throwable cause) {
        super(detail, null, cause);
    }

    @Override
    protected String defaultUserMessage() {
        return "实时语音链路异常";
    }
}
