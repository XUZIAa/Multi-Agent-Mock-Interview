package com.interviewer.domain.resume;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import java.util.List;

public record ProjectHighlight(String name, String role, List<String> stack,
                               String impact, String summary) {

    @JsonCreator
    public ProjectHighlight(@JsonProperty("name") String name,
                            @JsonProperty("role") String role,
                            @JsonProperty("stack") List<String> stack,
                            @JsonProperty("impact") String impact,
                            @JsonProperty("summary") String summary) {
        this.name = Text.safe(name);
        this.role = Text.safe(role);
        this.stack = stack == null ? List.of() : List.copyOf(stack);
        this.impact = Text.safe(impact);
        this.summary = Text.safe(summary);
    }
}
