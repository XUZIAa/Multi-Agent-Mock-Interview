package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tauri 的文件对话框返回真实路径，无需上传文件本体。 */
public record IngestPathBody(@Size(max = 64) String taskId, @NotBlank String path) {

    public IngestPathBody {
        taskId = Text.safe(taskId);
    }
}
