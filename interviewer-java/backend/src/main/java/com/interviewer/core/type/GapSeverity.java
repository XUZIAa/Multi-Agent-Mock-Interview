package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GapSeverity implements Labeled {

    BLOCKER("blocker", "致命缺口", 0),
    MAJOR("major", "重点缺口", 1),
    MINOR("minor", "次要缺口", 2);

    private final String value;
    private final String label;
    /** 排序权重。缺口越致命越靠前，复盘与诊断都按这个排。 */
    private final int order;

    GapSeverity(String value, String label, int order) {
        this.value = value;
        this.label = label;
        this.order = order;
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

    public int order() {
        return order;
    }

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static GapSeverity of(String raw) {
        return Labeled.parse(GapSeverity.class, raw, MAJOR);
    }
}
