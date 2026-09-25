package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.type.ScoreDimension;
import java.util.List;

public record DimensionScore(ScoreDimension dimension, double score, String reason,
                             List<String> evidence) {

    @JsonCreator
    public DimensionScore(@JsonProperty("dimension") ScoreDimension dimension,
                          @JsonProperty("score") double score,
                          @JsonProperty("reason") String reason,
                          @JsonProperty("evidence") List<String> evidence) {
        this.dimension = dimension;
        this.score = Text.clamp(score, 0.0, 100.0);
        this.reason = Text.safe(reason);
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
