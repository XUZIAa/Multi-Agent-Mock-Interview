package com.interviewer.domain.persona;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/** 表达风格。这些描述会原文进提示词，措辞是调过的，不要顺手改。 */
@Getter
public class SpeechStyle {

    private int codeSwitch = 0;
    private int verbosity = 4;
    private int warmth = 5;
    private int formality = 5;
    private int speechRate = 5;
    private List<String> catchphrases = new ArrayList<>();
    private List<String> bannedPhrases = new ArrayList<>();

    public void setCodeSwitch(int value) {
        this.codeSwitch = Scale.level(value);
    }

    public void setVerbosity(int value) {
        this.verbosity = Scale.level(value);
    }

    public void setWarmth(int value) {
        this.warmth = Scale.level(value);
    }

    public void setFormality(int value) {
        this.formality = Scale.level(value);
    }

    public void setSpeechRate(int value) {
        this.speechRate = Scale.level(value);
    }

    public void setCatchphrases(List<String> value) {
        this.catchphrases = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public void setBannedPhrases(List<String> value) {
        this.bannedPhrases = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    @JsonIgnore
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        // 语音场景下再啰嗦的人设也不能长篇，否则候选人根本跟不上
        lines.add("说话长度：" + Scale.of(verbosity,
                "一句话问完，绝不铺垫",
                "一到两句，直给",
                "两句以内，可带一句简短背景",
                "两句以内，语气可以更舒展但不加长"));
        // 温度决定用词软硬和给不给台阶，不决定给不给反馈。
        // 写成「肯定亮点」「主动鼓励」时，模型每轮都会先夸一句才问
        lines.add("态度温度：" + Scale.of(warmth,
                "冷硬，几乎不给情绪回应",
                "克制，只用「嗯」「好」这类短应答",
                "友善，用词偏软，他卡住时给个台阶",
                "亲切，语气放松，他紧张时安抚一句；但依然不评价他答得怎么样"));
        // 这是语音通话，再正式的人也是在说话。写「偏书面」会让它念稿
        lines.add("用语正式度：" + Scale.of(formality,
                "大白话，可以带口头禅和语气词",
                "自然口语，偶尔带俚语",
                "措辞讲究、术语准确，但仍然是说话，不是念稿",
                "用词严谨、称呼客气，句子依然要短、依然口语"));
        lines.add("语速：" + Scale.of(speechRate,
                "刻意放慢，每句之间留明显停顿",
                "从容不迫，句子之间有自然停顿",
                "偏快但仍咬字清楚，停顿短",
                "快节奏紧逼，但仍要让人听得清每个字"));
        if (codeSwitch >= 3) {
            lines.add("中英夹杂：" + Scale.of(codeSwitch,
                    "",
                    "偶尔用英文术语（如 deadline、owner）",
                    "高频中英混说，名词一律用英文",
                    "几乎每句夹英文短语，像外企内部会议"));
        }
        if (!catchphrases.isEmpty()) {
            lines.add("口头禅（自然穿插，不要每句都用）："
                    + String.join("、", catchphrases.subList(0, Math.min(6, catchphrases.size()))));
        }
        if (!bannedPhrases.isEmpty()) {
            lines.add("禁止说出的表达："
                    + String.join("、", bannedPhrases.subList(0, Math.min(8, bannedPhrases.size()))));
        }
        return lines;
    }
}
