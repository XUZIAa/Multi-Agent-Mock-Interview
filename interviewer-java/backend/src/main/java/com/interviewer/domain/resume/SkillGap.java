package com.interviewer.domain.resume;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.type.GapSeverity;

/** 技能盲区 + 可直接照着说的补救话术。 */
public record SkillGap(String skill, GapSeverity severity, String jdRequirement,
                       String whyGap, String bridgeAsset, String talkingScript,
                       String studyHint) {

    @JsonCreator
    public SkillGap(@JsonProperty("skill") String skill,
                    @JsonProperty("severity") GapSeverity severity,
                    @JsonProperty("jd_requirement") String jdRequirement,
                    @JsonProperty("why_gap") String whyGap,
                    @JsonProperty("bridge_asset") String bridgeAsset,
                    @JsonProperty("talking_script") String talkingScript,
                    @JsonProperty("study_hint") String studyHint) {
        this.skill = Text.safe(skill);
        this.severity = severity == null ? GapSeverity.MAJOR : severity;
        this.jdRequirement = Text.safe(jdRequirement);
        this.whyGap = Text.safe(whyGap);
        this.bridgeAsset = Text.safe(bridgeAsset);
        this.talkingScript = Text.safe(talkingScript);
        this.studyHint = Text.safe(studyHint);
    }
}
