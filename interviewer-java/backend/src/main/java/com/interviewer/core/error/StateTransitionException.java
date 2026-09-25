package com.interviewer.core.error;

public class StateTransitionException extends InterviewerException {

    public StateTransitionException(String detail) {
        super(detail);
    }

    @Override
    protected String defaultUserMessage() {
        return "当前阶段不允许该操作";
    }
}
