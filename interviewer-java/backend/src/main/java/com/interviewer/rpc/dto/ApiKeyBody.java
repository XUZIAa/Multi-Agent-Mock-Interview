package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApiKeyBody(@NotBlank @Size(max = 60) String providerKey, String apiKey) {

    public ApiKeyBody {
        apiKey = Text.safe(apiKey);
    }
}
