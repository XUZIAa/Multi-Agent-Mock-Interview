package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RunCodeBody(@NotBlank @Size(max = 40) String language,
                          @Size(max = 60_000) String source,
                          @Size(max = 20_000) String stdin) {

    public RunCodeBody {
        source = Text.safe(source);
        stdin = Text.safe(stdin);
    }
}
