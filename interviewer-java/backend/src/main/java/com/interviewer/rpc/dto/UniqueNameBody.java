package com.interviewer.rpc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UniqueNameBody(@NotBlank @Size(max = 60) String base) {
}
