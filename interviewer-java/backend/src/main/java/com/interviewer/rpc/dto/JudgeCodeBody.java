package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import com.interviewer.domain.coding.CodingCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record JudgeCodeBody(@NotBlank @Size(max = 40) String language,
                            @Size(max = 60_000) String source,
                            @Size(max = 12) List<CodingCase> cases) {

    public JudgeCodeBody {
        source = Text.safe(source);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
