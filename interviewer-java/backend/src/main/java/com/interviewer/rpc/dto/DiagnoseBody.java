package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.Size;

public record DiagnoseBody(@Size(max = 64) String taskId, int resumeId, int jobId,
                           boolean refresh) {

    public DiagnoseBody {
        taskId = Text.safe(taskId);
    }
}
