package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;

public record DrillItem(String action, String why, String timeCost) {

    @JsonCreator
    public DrillItem(@JsonProperty("action") String action,
                     @JsonProperty("why") String why,
                     @JsonProperty("time_cost") String timeCost) {
        this.action = Text.safe(action);
        this.why = Text.safe(why);
        this.timeCost = Text.safe(timeCost);
    }
}
