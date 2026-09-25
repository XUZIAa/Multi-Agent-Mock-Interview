package com.interviewer.domain.persona;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import lombok.Getter;

/** 压迫感设定。打断倾向同时被引擎用来算打断阈值，不只是提示词文案。 */
@Getter
public class PressureProfile {

    private int aggression = 4;
    private int interruptTendency = 3;
    private int silencePressure = 2;
    private int challengeFrequency = 4;
    private int toleranceForVagueness = 5;

    public void setAggression(int value) {
        this.aggression = Scale.level(value);
    }

    public void setInterruptTendency(int value) {
        this.interruptTendency = Scale.level(value);
    }

    public void setSilencePressure(int value) {
        this.silencePressure = Scale.level(value);
    }

    public void setChallengeFrequency(int value) {
        this.challengeFrequency = Scale.level(value);
    }

    public void setToleranceForVagueness(int value) {
        this.toleranceForVagueness = Scale.level(value);
    }

    @JsonIgnore
    public List<String> describe() {
        return List.of(
                "攻击性：" + Scale.of(aggression,
                        "完全不否定对方，只提问",
                        "会温和指出问题",
                        "直接质疑结论，语气偏硬",
                        "毫不客气地否定，甚至流露不耐烦"),
                "打断倾向：" + Scale.of(interruptTendency,
                        "绝不打断，等对方说完",
                        "只在明显跑题时打断",
                        "对方超过半分钟没讲到重点就打断",
                        "只要听到废话立刻插话，抢节奏"),
                "沉默施压：" + Scale.of(silencePressure,
                        "不使用沉默",
                        "偶尔停顿两秒再接话",
                        "常用沉默让对方自己补充",
                        "大量使用长沉默制造不适"),
                "质疑频率：" + Scale.of(challengeFrequency,
                        "基本不反问",
                        "关键结论会反问一次",
                        "每个回答都会挑一处深挖",
                        "句句设疑，逼对方自证"),
                "对含糊回答的容忍度：" + Scale.of(toleranceForVagueness,
                        "零容忍，必须给出具体数字和细节",
                        "会追一次要细节",
                        "接受概述，但会记下",
                        "不强求细节"));
    }
}
