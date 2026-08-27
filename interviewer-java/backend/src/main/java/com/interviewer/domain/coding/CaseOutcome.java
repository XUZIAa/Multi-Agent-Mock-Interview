package com.interviewer.domain.coding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;

public record CaseOutcome(int index, boolean passed, String input, String expected,
                          String actual, String stderr, long durationMs, boolean timedOut) {

    @JsonCreator
    public CaseOutcome(@JsonProperty("index") int index,
                       @JsonProperty("passed") boolean passed,
                       @JsonProperty("input") String input,
                       @JsonProperty("expected") String expected,
                       @JsonProperty("actual") String actual,
                       @JsonProperty("stderr") String stderr,
                       @JsonProperty("duration_ms") long durationMs,
                       @JsonProperty("timed_out") boolean timedOut) {
        this.index = index;
        this.passed = passed;
        this.input = Text.safe(input);
        this.expected = Text.safe(expected);
        this.actual = Text.safe(actual);
        this.stderr = Text.safe(stderr);
        this.durationMs = durationMs;
        this.timedOut = timedOut;
    }
}
