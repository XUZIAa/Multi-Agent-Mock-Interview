package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.JobLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SynthesizeJobBody(@Size(max = 64) String taskId,
                                @NotBlank @Size(max = 120) String title,
                                @NotNull CompanyTier tier,
                                @NotNull JobLevel level,
                                String extra) {

    public SynthesizeJobBody {
        taskId = Text.safe(taskId);
        extra = Text.safe(extra);
    }
}
