package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;

/** 面试中被放弃深挖的技能点。这是最该补的短板。 */
public record AbandonedSkill(String skill, String domain, int abandonedAtDepth, int attempts) {

    @JsonCreator
    public AbandonedSkill(@JsonProperty("skill") String skill,
                          @JsonProperty("domain") String domain,
                          @JsonProperty("abandoned_at_depth") int abandonedAtDepth,
                          @JsonProperty("attempts") int attempts) {
        this.skill = Text.safe(skill);
        this.domain = Text.safe(domain);
        this.abandonedAtDepth = abandonedAtDepth <= 0 ? 1 : abandonedAtDepth;
        this.attempts = attempts;
    }
}
