package com.interviewer.core.error;

/**
 * 应用内所有异常的根，便于接口层统一拦截展示。
 *
 * <p>{@code userMessage} 是给用户看的话术，{@code detail} 是给日志看的原因。
 * 两者分开是刻意的：把堆栈或服务端原文直接弹给用户，等于什么都没说。
 */
public class InterviewerException extends RuntimeException {

    private static final String DEFAULT_USER_MESSAGE = "发生未知错误";

    private final String userMessage;
    private final String detail;

    public InterviewerException() {
        this("", null, null);
    }

    public InterviewerException(String detail) {
        this(detail, null, null);
    }

    public InterviewerException(String detail, String userMessage) {
        this(detail, userMessage, null);
    }

    public InterviewerException(String detail, String userMessage, Throwable cause) {
        super(pick(detail, userMessage, DEFAULT_USER_MESSAGE), cause);
        this.detail = detail == null ? "" : detail;
        this.userMessage = userMessage != null && !userMessage.isBlank()
                ? userMessage
                : defaultUserMessage();
    }

    /** 子类覆盖它给出自己的默认话术，对应 Python 里的类属性 user_message。 */
    protected String defaultUserMessage() {
        return DEFAULT_USER_MESSAGE;
    }

    public String userMessage() {
        return userMessage;
    }

    public String detail() {
        return detail;
    }

    private static String pick(String detail, String userMessage, String fallback) {
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        return userMessage != null && !userMessage.isBlank() ? userMessage : fallback;
    }
}
