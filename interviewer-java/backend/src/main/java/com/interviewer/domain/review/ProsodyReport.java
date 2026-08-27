package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import java.util.Comparator;
import java.util.List;

/** 副语言指标全部由规则计算，不交给模型编造。 */
public record ProsodyReport(double wordsPerMinute, double fillerRatio, double pauseRatio,
                            long longestPauseMs, double speakingRatio, int interruptedCount,
                            List<QuestionProsody> perQuestion, String verdict) {

    @JsonCreator
    public ProsodyReport(@JsonProperty("words_per_minute") double wordsPerMinute,
                         @JsonProperty("filler_ratio") double fillerRatio,
                         @JsonProperty("pause_ratio") double pauseRatio,
                         @JsonProperty("longest_pause_ms") long longestPauseMs,
                         @JsonProperty("speaking_ratio") double speakingRatio,
                         @JsonProperty("interrupted_count") int interruptedCount,
                         @JsonProperty("per_question") List<QuestionProsody> perQuestion,
                         @JsonProperty("verdict") String verdict) {
        this.wordsPerMinute = wordsPerMinute;
        this.fillerRatio = fillerRatio;
        this.pauseRatio = pauseRatio;
        this.longestPauseMs = longestPauseMs;
        this.speakingRatio = speakingRatio;
        this.interruptedCount = interruptedCount;
        this.perQuestion = perQuestion == null ? List.of() : List.copyOf(perQuestion);
        this.verdict = Text.safe(verdict);
    }

    public static ProsodyReport empty(String verdict) {
        return new ProsodyReport(0, 0, 0, 0, 0, 0, List.of(), verdict);
    }

    /** 结论要基于已算好的指标，所以先造报告再回填这一句。 */
    public ProsodyReport withVerdict(String text) {
        return new ProsodyReport(wordsPerMinute, fillerRatio, pauseRatio, longestPauseMs,
                speakingRatio, interruptedCount, perQuestion, text);
    }

    /** 语速最快、停顿最多的那一题。问题最集中的地方最值得复盘。 */
    @JsonIgnore
    public QuestionProsody worstQuestion() {
        return perQuestion.stream()
                .max(Comparator.comparingDouble(QuestionProsody::wordsPerMinute)
                        .thenComparingDouble(QuestionProsody::pauseRatio))
                .orElse(null);
    }
}
