package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AnnotationKind implements Labeled {

    STRENGTH("strength", "亮点"),
    WEAKNESS("weakness", "待改进"),
    FILLER("filler", "冗余表达"),
    OFF_TOPIC("off_topic", "偏离问题");

    private final String value;
    private final String label;

    AnnotationKind(String value, String label) {
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
    public static AnnotationKind of(String raw) {
        return Labeled.find(AnnotationKind.class, raw).orElse(null);
    }
}
