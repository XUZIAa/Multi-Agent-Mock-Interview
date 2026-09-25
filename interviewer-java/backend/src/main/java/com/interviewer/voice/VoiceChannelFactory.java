package com.interviewer.voice;

import com.interviewer.core.config.CredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 语音通道的创建入口。
 *
 * <p>抽出工厂是为了让编排层不认识具体实现：语音真正跑在 Python sidecar 里，
 * 而编排层只需要一个能发指令、能收事件的对象。
 */
@Component
public class VoiceChannelFactory {

    private final VoiceSidecar sidecar;
    private final CredentialStore credentials;
    private final ObjectMapper mapper;

    public VoiceChannelFactory(VoiceSidecar sidecar, CredentialStore credentials,
                               ObjectMapper mapper) {
        this.sidecar = sidecar;
        this.credentials = credentials;
        this.mapper = mapper;
    }

    public VoiceChannel open(VoiceSink sink, int sessionId) {
        return new SidecarVoiceChannel(sidecar, credentials, mapper, sink, sessionId);
    }
}
