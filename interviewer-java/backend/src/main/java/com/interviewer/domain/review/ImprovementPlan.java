package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import java.util.List;

/** 专项提升方案。一个专项对应一条明确的训练路径，不给泛泛建议。 */
public record ImprovementPlan(String focusArea, String diagnosis, String expectedGain,
                              List<DrillItem> drills, List<String> resources,
                              String nextMockSetup) {

    @JsonCreator
    public ImprovementPlan(@JsonProperty("focus_area") String focusArea,
                           @JsonProperty("diagnosis") String diagnosis,
                           @JsonProperty("expected_gain") String expectedGain,
                           @JsonProperty("drills") List<DrillItem> drills,
                           @JsonProperty("resources") List<String> resources,
                           @JsonProperty("next_mock_setup") String nextMockSetup) {
        this.focusArea = Text.safe(focusArea);
        this.diagnosis = Text.safe(diagnosis);
        this.expectedGain = Text.safe(expectedGain);
        this.drills = drills == null ? List.of() : List.copyOf(drills);
        this.resources = resources == null ? List.of() : List.copyOf(resources);
        this.nextMockSetup = Text.safe(nextMockSetup);
    }
}
