package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IngestJobTextBody(@Size(max = 64) String taskId, @NotBlank String raw) {

    public IngestJobTextBody {
        taskId = Text.safe(taskId);
    }
}
