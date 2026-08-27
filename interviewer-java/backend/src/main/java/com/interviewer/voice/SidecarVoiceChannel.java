package com.interviewer.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.Text;
import com.interviewer.core.config.AppSettings;
import com.interviewer.core.config.AudioSettings;
import com.interviewer.core.config.CredentialStore;
import com.interviewer.core.provider.RealtimeProvider;
import com.interviewer.domain.persona.PersonaContract;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 Python sidecar 实现的语音通道。
 *
 * <p>行协议的两侧职责划分：Java 管「说什么」（人格锚点、导演指令、打断时机），
 * Python 管「怎么发声」（采集、重采样、回声抑制、播放缓冲、实时协议）。
 *
 * <p>{@code cueToken} 是这套设计的关键一环：Java 下发指令时附一个不透明标记，
 * Python 在首个音频块真正出声时把它原样送回。没有它，界面就只能跟着导演的时间轴走，
 * 而那比耳朵快一整轮。
 */
class SidecarVoiceChannel implements VoiceChannel {

    private static final Logger log = LoggerFactory.getLogger(SidecarVoiceChannel.class);

    private final VoiceSidecar sidecar;
    private final CredentialStore credentials;
    private final ObjectMapper mapper;
    private final VoiceSink sink;
    private final int sessionId;
    /** 一场 45 分钟的双轨录音有一百多兆，合并是纯顺序 IO，给足余量。 */
    private static final Duration AUDIO_MERGE_TIMEOUT = Duration.ofSeconds(120);

    private final AtomicReference<VoiceStatus> status = new AtomicReference<>(VoiceStatus.idle());
    private final AtomicReference<String> audioPath = new AtomicReference<>("");
    private final CountDownLatch audioSaved = new CountDownLatch(1);

    SidecarVoiceChannel(VoiceSidecar sidecar, CredentialStore credentials, ObjectMapper mapper,
                        VoiceSink sink, int sessionId) {
        this.sidecar = sidecar;
        this.credentials = credentials;
        this.mapper = mapper;
        this.sink = sink;
        this.sessionId = sessionId;
    }

    @Override
    public void connect(String instructions, AppSettings settings, PersonaContract persona) {
        sidecar.ensureStarted(this::onEvent);

        RealtimeProvider provider = settings.getRealtime().catalog();
        AudioSettings audio = settings.getAudio();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmd", "connect");
        payload.put("session_id", sessionId);
        payload.put("instructions", instructions);

        Map<String, Object> providerCfg = new LinkedHashMap<>();
        providerCfg.put("key", provider.key());
        providerCfg.put("ws_url", provider.wsUrl());
        providerCfg.put("model", settings.getRealtime().resolvedModel());
        providerCfg.put("voice", Text.notBlank(persona.getVoice())
                ? persona.getVoice() : settings.getRealtime().resolvedVoice());
        providerCfg.put("temperature", settings.getRealtime().getTemperature());
        providerCfg.put("input_sample_rate", provider.inputSampleRate());
        providerCfg.put("output_sample_rate", provider.outputSampleRate());
        providerCfg.put("audio_format", provider.audioFormat());
        providerCfg.put("supports_semantic_vad", provider.supportsSemanticVad());
        // Key 只在建链这一刻过一次管道，不落任何日志
        providerCfg.put("api_key", credentials.require(provider.credentialKey()));
        payload.put("provider", providerCfg);

        Map<String, Object> audioCfg = new LinkedHashMap<>();
        audioCfg.put("input_device", audio.getInputDevice());
        audioCfg.put("output_device", audio.getOutputDevice());
        audioCfg.put("input_gain", audio.getInputGain());
        audioCfg.put("vad_threshold", audio.getVadThreshold());
        audioCfg.put("silence_duration_ms", audio.getSilenceDurationMs());
        audioCfg.put("prefix_padding_ms", audio.getPrefixPaddingMs());
        audioCfg.put("semantic_vad", audio.isSemanticVad());
        audioCfg.put("auto_gain", audio.isAutoGain());
        audioCfg.put("learned_gain", audio.getLearnedGain());
        audioCfg.put("playback_buffer_ms", audio.getPlaybackBufferMs());
        payload.put("audio", audioCfg);

        payload.put("save_audio", settings.getFeatures().isSaveAudio());
        sidecar.send(payload);
    }

    @Override
    public void sendDirective(String directive, String cueToken) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmd", "directive");
        payload.put("text", directive);
        payload.put("cue", cueToken);
        sidecar.send(payload);
    }

    @Override
    public void reanchor(String instructions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmd", "reanchor");
        payload.put("instructions", instructions);
        sidecar.send(payload);
    }

    @Override
    public void bargeIn(String directive) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmd", "barge_in");
        payload.put("text", directive);
        sidecar.send(payload);
    }

    @Override
    public void cancelCurrentResponse() {
        sidecar.send(Map.of("cmd", "cancel"));
    }

    @Override
    public void setMuted(boolean muted) {
        sidecar.send(Map.of("cmd", "mute", "muted", muted));
    }

    @Override
    public VoiceStatus status() {
        return status.get();
    }

    /**
     * 收尾并等录音落盘。
     *
     * <p>必须等：双轨合并要读写几十兆，而返回值紧接着就要写进 session 表。不等就永远存进
     * 一个空路径，录音文件躺在磁盘上没人认领。
     */
    @Override
    public String close(String reason) {
        try {
            sidecar.send(Map.of("cmd", "close", "reason", Text.safe(reason)));
        } catch (Exception e) {
            log.info("语音通道已不可用，跳过收尾命令: {}", e.getMessage());
            return audioPath.get();
        }
        try {
            if (!audioSaved.await(AUDIO_MERGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("等录音合并超过 {} 秒，本场录音路径不入库",
                        AUDIO_MERGE_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return audioPath.get();
    }

    /** 事件分派。全部只做转发，状态变更由编排层在自己的循环线程上做。 */
    private void onEvent(JsonNode event) {
        String type = event.path("event").asText("");
        switch (type) {
            case "state" -> sink.onState(event.path("connected").asBoolean(false),
                    event.path("reason").asText(""));
            case "candidate_speech" -> sink.onCandidateSpeech(
                    event.path("speaking").asBoolean(false));
            case "candidate_text" -> sink.onCandidateText(
                    event.path("text").asText(""), event.path("final").asBoolean(false),
                    event.path("started_at_ms").asLong(0), event.path("duration_ms").asLong(0));
            case "interviewer_text" -> sink.onInterviewerText(
                    event.path("text").asText(""), event.path("final").asBoolean(false),
                    event.path("started_at_ms").asLong(0), event.path("duration_ms").asLong(0));
            case "voice_open" -> sink.onVoiceOpen(
                    event.path("cue").isNull() ? null : event.path("cue").asText(),
                    event.path("lead_ms").asLong(0));
            case "response" -> sink.onResponse(event.path("active").asBoolean(false));
            case "barge_in" -> sink.onBargeIn();
            case "status" -> {
                VoiceStatus fresh = new VoiceStatus(
                        event.path("connected").asBoolean(false),
                        event.path("responding").asBoolean(false),
                        event.path("pending_ms").asLong(0),
                        event.path("candidate_level").asDouble(0),
                        event.path("voice_level").asDouble(0),
                        event.path("auto_gain").asDouble(1.0));
                status.set(fresh);
                sink.onVoiceStatus(fresh);
            }
            case "audio_saved" -> {
                audioPath.set(event.path("path").asText(""));
                audioSaved.countDown();
            }
            case "error" -> sink.onError(event.path("message").asText("语音链路异常"),
                    event.path("fatal").asBoolean(false));
            default -> log.debug("忽略未知语音事件: {}", type);
        }
    }
}
