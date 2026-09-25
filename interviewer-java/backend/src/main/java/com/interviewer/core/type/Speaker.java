package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Speaker implements Labeled {

    INTERVIEWER("interviewer", "面试官"),
    CANDIDATE("candidate", "我");

    private final String value;
    private final String label;

    Speaker(String value, String label) {
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
    public static Speaker of(String raw) {
        return Labeled.parse(Speaker.class, raw, CANDIDATE);
    }
}
