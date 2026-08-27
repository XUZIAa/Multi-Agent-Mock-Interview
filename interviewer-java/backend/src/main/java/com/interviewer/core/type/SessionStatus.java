package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SessionStatus implements Labeled {

    DRAFT("draft", "未开始"),
    RUNNING("running", "进行中"),
    REVIEWING("reviewing", "生成复盘"),
    COMPLETED("completed", "已完成"),
    ABORTED("aborted", "已中止");

    private final String value;
    private final String label;

    SessionStatus(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static SessionStatus of(String raw) {
        return Labeled.parse(SessionStatus.class, raw, DRAFT);
    }
}
