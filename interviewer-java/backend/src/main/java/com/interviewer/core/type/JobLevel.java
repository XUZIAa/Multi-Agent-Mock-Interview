package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum JobLevel implements Labeled {

    INTERN("intern", "实习 / 应届", "在校或 1 年以内"),
    JUNIOR("junior", "初级（1-3 年）", "1-3 年"),
    MID("mid", "中级（3-5 年）", "3-5 年"),
    SENIOR("senior", "高级（5-8 年）", "5-8 年"),
    EXPERT("expert", "专家 / 架构（8 年以上）", "8 年以上");

    private final String value;
    private final String label;
    private final String yearsHint;

    JobLevel(String value, String label, String yearsHint) {
        this.value = value;
        this.label = label;
        this.yearsHint = yearsHint;
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

    public String yearsHint() {
        return yearsHint;
    }

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static JobLevel of(String raw) {
        return Labeled.parse(JobLevel.class, raw, MID);
    }
}
