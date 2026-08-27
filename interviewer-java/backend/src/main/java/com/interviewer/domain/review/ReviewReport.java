package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.Text;
import com.interviewer.core.type.ScoreDimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 复盘报告。分阶段拼装：评分/批注/重构/错题四个子任务并发产出后汇总，
 * 专项提升方案基于汇总结果再生成一次，所以这里是可变的。
 */
@Getter
@Setter
public class ReviewReport {

    private int sessionId;
    private long durationMs = 0;
    private boolean reviewable = true;
    private double overallScore = 0.0;
    private String headline = "";
    private String summary = "";
    private List<DimensionScore> dimensions = new ArrayList<>();
    private List<TranscriptAnnotation> annotations = new ArrayList<>();
    private List<AnswerRewrite> rewrites = new ArrayList<>();
    private List<MistakeItem> mistakes = new ArrayList<>();
    private ProsodyReport prosody = ProsodyReport.empty("");
    private List<String> strengths = new ArrayList<>();
    private List<String> improvements = new ArrayList<>();
    private List<String> nextActions = new ArrayList<>();
    private List<ImprovementPlan> improvementPlans = new ArrayList<>();
    private List<AbandonedSkill> abandonedSkills = new ArrayList<>();

    /**
     * 生成失败的板块名。
     *
     * <p>四个子任务里评分是骨架、其余是血肉。血肉缺了报告仍有用，但必须让用户知道是
     * 「这一步没跑成」而不是「模型觉得没什么可说的」——空数组和生成失败在界面上长得一样。
     */
    private List<String> degradedSections = new ArrayList<>();

    public void setOverallScore(double value) {
        this.overallScore = Text.clamp(value, 0.0, 100.0);
    }

    @JsonIgnore
    public Map<ScoreDimension, Double> scoreMap() {
        Map<ScoreDimension, Double> map = new LinkedHashMap<>();
        dimensions.forEach(d -> map.put(d.dimension(), d.score()));
        return map;
    }

    @JsonIgnore
    public List<TranscriptAnnotation> annotationsFor(int turnIndex) {
        return annotations.stream().filter(a -> a.turnIndex() == turnIndex).toList();
    }
}
