package com.interviewer.domain.persona;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.interviewer.core.type.Labeled;

public enum PersonaArchetype implements Labeled {

    IRRITABLE_CTO("irritable_cto", "暴躁的 CTO"),
    GENTLE_HR("gentle_hr", "温和的 HR"),
    PICKY_BIZ_LEADER("picky_biz_leader", "刁钻的业务线 Leader"),
    FOREIGN_CORP("foreign_corp", "中英夹杂的外企 Manager"),
    ACADEMIC_PURIST("academic_purist", "抠原理的学术派"),
    SILENT_OBSERVER("silent_observer", "沉默施压的观察者"),
    RAPID_FIRE("rapid_fire", "连环追问的快枪手"),
    STRUCTURED("structured", "按提纲推进的标准型"),
    CUSTOM("custom", "自定义人设");

    private final String value;
    private final String label;

    PersonaArchetype(String value, String label) {
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
    public static PersonaArchetype of(String raw) {
        return Labeled.parse(PersonaArchetype.class, raw, CUSTOM);
    }
}
