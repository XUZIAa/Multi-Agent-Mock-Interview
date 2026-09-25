package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.JobLevel;
import com.interviewer.domain.persona.PersonaContract;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BuildSessionBody(@Size(max = 64) String taskId,
                               @NotNull PersonaContract persona,
                               // 不选简历/岗位时就是空的，纯人设面试
                               @Schema(nullable = true) Integer resumeId,
                               @Schema(nullable = true) Integer jobId,
                               @NotNull CompanyTier tier,
                               @NotNull JobLevel level,
                               @Min(5) @Max(120) int minutes,
                               boolean codingEnabled) {

    public BuildSessionBody {
        taskId = Text.safe(taskId);
    }
}
