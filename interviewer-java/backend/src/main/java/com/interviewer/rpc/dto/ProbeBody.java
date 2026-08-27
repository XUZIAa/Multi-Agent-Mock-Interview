package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 探测某个供应商的密钥与模型是否真的能用。
 *
 * <p>密钥留空则用已保存的那份，这样用户不必为了测试重新粘一遍。
 */
public record ProbeBody(@NotBlank @Size(max = 60) String providerKey,
                        @Size(max = 120) String model,
                        String apiKey,
                        boolean realtime) {

    public ProbeBody {
        model = Text.safe(model);
        apiKey = Text.safe(apiKey);
    }
}
