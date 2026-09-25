package com.interviewer.domain.coding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record JudgeOutcome(int passed, int total, List<CaseOutcome> cases) {

    @JsonCreator
    public JudgeOutcome(@JsonProperty("passed") int passed,
                        @JsonProperty("total") int total,
                        @JsonProperty("cases") List<CaseOutcome> cases) {
        this.passed = passed;
        this.total = total;
        this.cases = cases == null ? List.of() : List.copyOf(cases);
    }

    @JsonIgnore
    public boolean allPassed() {
        return total > 0 && passed == total;
    }

    public static JudgeOutcome of(List<CaseOutcome> outcomes) {
        int ok = (int) outcomes.stream().filter(CaseOutcome::passed).count();
        return new JudgeOutcome(ok, outcomes.size(), outcomes);
    }
}
