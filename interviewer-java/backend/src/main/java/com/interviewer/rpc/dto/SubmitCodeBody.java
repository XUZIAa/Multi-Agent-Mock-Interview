package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitCodeBody(@NotBlank @Size(max = 40) String language, String source) {

    public SubmitCodeBody {
        source = Text.safe(source);
    }
}
