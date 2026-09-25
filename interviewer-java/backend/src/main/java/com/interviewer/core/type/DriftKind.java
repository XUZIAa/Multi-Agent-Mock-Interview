package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 人格漂移的具体形态，Guard 命中后用于决定修复动作。 */
public enum DriftKind implements Labeled {

    NONE("none", "无", "无"),
    AI_SELF_REVEAL("ai_self_reveal", "暴露 AI 身份", "暴露了 AI 身份或系统设定"),
    ROLE_SWAP("role_swap", "角色错位", "脱离了面试官角色"),
    REFUSAL("refusal", "拒答", "用助手口吻拒绝了"),
    OFF_DOMAIN("off_domain", "跑出领域", "聊到了与面试无关的话题"),
    STYLE_BREAK("style_break", "风格断裂", "违背了人设的语气设定"),
    ANSWER_LEAK("answer_leak", "泄露答案", "把答案泄露给了候选人");

    private final String value;
    private final String label;
    /** 写进纠正指令里的那句话，告诉模型它刚才错在哪。 */
    private final String repairHint;

    DriftKind(String value, String label, String repairHint) {
        this.value = value;
        this.label = label;
        this.repairHint = repairHint;
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

    public String repairHint() {
        return repairHint;
    }

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static DriftKind of(String raw) {
        return Labeled.parse(DriftKind.class, raw, NONE);
    }

    public boolean violated() {
        return this != NONE;
    }
}
