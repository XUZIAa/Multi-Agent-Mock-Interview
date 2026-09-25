package com.interviewer.voice;

/**
 * 语音链路回调给编排层的事件。
 *
 * <p>与 Python 版的 RealtimeSink 一一对应。所有回调都从语音 sidecar 的读线程进来，
 * 实现方必须立刻转投到 {@code SessionLoop}，不要在这里改状态。
 */
public interface VoiceSink {

    void onState(boolean connected, String reason);

    /** VAD 判定的说话起止。 */
    void onCandidateSpeech(boolean speaking);

    /**
     * 候选人的转写。
     *
     * @param finalText true 表示整句已定稿，false 是流式增量
     */
    void onCandidateText(String text, boolean finalText, long startedAtMs, long durationMs);

    /**
     * 面试官的转写。
     *
     * <p>只在 final 时有用：面试官的文本比它的声音早好几秒，逐字上屏会把下一题提前剧透。
     */
    void onInterviewerText(String text, boolean finalText, long startedAtMs, long durationMs);

    /**
     * 本轮语音真正开口。
     *
     * <p>cueToken 是编排层下发指令时给的不透明标记，原样回来。导演的时间轴比耳朵快一整轮，
     * 提词器和状态栏必须等这一刻才更新，否则用户会看到下一题、听到上一题。
     *
     * @param leadMs 距真正出声还有多久
     */
    void onVoiceOpen(String cueToken, long leadMs);

    /** 生成结束。不等于说完——缓冲里还压着音频。 */
    void onResponse(boolean active);

    /** 候选人打断了面试官。 */
    void onBargeIn();

    /**
     * 语音侧的状态推送。
     *
     * <p>跨进程之后编排层不能再同步读播放缓冲深度，只能由语音侧按心跳推上来。
     * 这是与 Python 单进程版最实质的一处差异。
     */
    void onVoiceStatus(VoiceStatus status);

    void onError(String message, boolean fatal);
}
