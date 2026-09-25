package com.interviewer.domain.interview;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.type.InterviewPhase;

/** 一个环节的时间预算与最少题数。面试开始前排定，运行期只读。 */
public record PhaseSlot(InterviewPhase phase, long budgetMs, int minQuestions) {

    @JsonCreator
    public PhaseSlot(@JsonProperty("phase") InterviewPhase phase,
                     @JsonProperty("budget_ms") long budgetMs,
                     @JsonProperty("min_questions") int minQuestions) {
        this.phase = phase;
        this.budgetMs = budgetMs;
        // 0 表示 JSON 里没这一项。至少要问一题，否则环节等于没排
        this.minQuestions = minQuestions <= 0 ? 1 : minQuestions;
    }
}
