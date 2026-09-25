package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 题目来源。决定这道题为什么值得问，也决定它能不能被跳过。 */
public enum QuestionSource implements Labeled {

    JD_REQUIREMENT("jd", "JD 硬性要求", 0),
    RESUME_PROJECT("project", "简历项目", 1),
    RESUME_SKILL("skill", "简历技能", 2),
    CODING("coding", "编码题", 3),
    FUNDAMENTAL("fundamental", "岗位基础", 4),
    BEHAVIORAL("behavioral", "行为与价值观", 5);

    private final String value;
    private final String label;
    /** 选题优先级。JD 硬性要求最先问，行为题垫底。 */
    private final int priority;

    QuestionSource(String value, String label, int priority) {
        this.value = value;
        this.label = label;
        this.priority = priority;
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

    public int priority() {
        return priority;
    }

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static QuestionSource of(String raw) {
        return Labeled.parse(QuestionSource.class, raw, FUNDAMENTAL);
    }
}
