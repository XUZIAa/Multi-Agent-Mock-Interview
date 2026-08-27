package com.interviewer.domain.resume;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;

public record SkillMatch(String skill, String evidence, int strength) {

    @JsonCreator
    public SkillMatch(@JsonProperty("skill") String skill,
                      @JsonProperty("evidence") String evidence,
                      @JsonProperty("strength") int strength) {
        this.skill = Text.safe(skill);
        this.evidence = Text.safe(evidence);
        // 有效区间是 1~5，0 只可能是没给值，按中位处理
        this.strength = strength == 0 ? 3 : Math.max(1, Math.min(5, strength));
    }
}
