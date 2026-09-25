package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 公司类型。它决定考什么、怎么问、以及分数怎么加权。 */
public enum CompanyTier implements Labeled {

    BIG_TECH("big_tech", "互联网大厂"),
    MID_TECH("mid_tech", "中型科技公司"),
    STARTUP("startup", "创业公司"),
    MANUFACTURING("manufacturing", "制造业 / 工业软件"),
    STATE_OWNED("state_owned", "国企 / 事业单位"),
    FOREIGN("foreign", "外企 / 跨国研发中心"),
    FINANCE("finance", "银行 / 券商 / 金融科技"),
    OUTSOURCE("outsource", "外包 / 乙方交付");

    private final String value;
    private final String label;

    CompanyTier(String value, String label) {
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
    public static CompanyTier of(String raw) {
        return Labeled.parse(CompanyTier.class, raw, MID_TECH);
    }
}
