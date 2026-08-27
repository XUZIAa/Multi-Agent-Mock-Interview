package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum StarElement implements Labeled {

    SITUATION("situation", "情境 Situation"),
    TASK("task", "任务 Task"),
    ACTION("action", "行动 Action"),
    RESULT("result", "结果 Result");

    private final String value;
    private final String label;

    StarElement(String value, String label) {
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
    public static StarElement of(String raw) {
        return Labeled.find(StarElement.class, raw).orElse(null);
    }
}
