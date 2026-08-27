package com.interviewer.core.error;

public class InterviewBusyException extends InterviewerException {

    public InterviewBusyException() {
        super("");
    }

    @Override
    protected String defaultUserMessage() {
        return "已有面试正在进行，请先结束当前面试再开始新的一场";
    }
}
