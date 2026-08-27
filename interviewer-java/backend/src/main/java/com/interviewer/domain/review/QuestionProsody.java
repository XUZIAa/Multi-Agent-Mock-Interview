package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record QuestionProsody(int questionIndex, double wordsPerMinute, int fillerCount,
                              double pauseRatio, long longestPauseMs) {

    @JsonCreator
    public QuestionProsody(@JsonProperty("question_index") int questionIndex,
                           @JsonProperty("words_per_minute") double wordsPerMinute,
                           @JsonProperty("filler_count") int fillerCount,
                           @JsonProperty("pause_ratio") double pauseRatio,
                           @JsonProperty("longest_pause_ms") long longestPauseMs) {
        this.questionIndex = questionIndex;
        this.wordsPerMinute = wordsPerMinute;
        this.fillerCount = fillerCount;
        this.pauseRatio = pauseRatio;
        this.longestPauseMs = longestPauseMs;
    }
}
