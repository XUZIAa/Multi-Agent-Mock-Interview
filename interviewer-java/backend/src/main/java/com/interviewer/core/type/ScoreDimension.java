package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ScoreDimension implements Labeled {

    TECH_DEPTH("tech_depth", "技术深度"),
    EXPRESSION("expression", "逻辑表达"),
    RESILIENCE("resilience", "抗压能力"),
    VALUE_FIT("value_fit", "价值观匹配"),
    CODING("coding", "编码能力"),
    COLLABORATION("collaboration", "沟通协作");

    private final String value;
    private final String label;

    ScoreDimension(String value, String label) {
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
    public static ScoreDimension of(String raw) {
        return Labeled.find(ScoreDimension.class, raw).orElse(null);
    }
}
