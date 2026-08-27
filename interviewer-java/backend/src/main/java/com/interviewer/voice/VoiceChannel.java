package com.interviewer.voice;

import com.interviewer.core.config.AppSettings;
import com.interviewer.domain.persona.PersonaContract;

/**
 * 编排层对语音链路的控制面。
 *
 * <p>与 Python 版的 RealtimeClient 对应。刻意做成接口：真正的音频采集、播放、回声抑制、
 * 实时协议都在 Python sidecar 里，Java 侧只发指令、收事件。
 */
public interface VoiceChannel {

    /** 建链并下发首份人格锚点。 */
    void connect(String instructions, AppSettings settings, PersonaContract persona);

    /**
     * 下发一条导演指令。
     *
     * @param cueToken 不透明标记，语音真正开口时原样回来。null 表示这一轮不需要联动界面
     */
    void sendDirective(String directive, String cueToken);

    /** 整体重发人格锚点。不清空对话，只换 session 指令。 */
    void reanchor(String instructions);

    /** 面试官主动插话：清空播放队列并立刻发新指令。 */
    void bargeIn(String directive);

    /** 取消当前生成。用户点打断、或守卫发现漂移时用。 */
    void cancelCurrentResponse();

    void setMuted(boolean muted);

    /** 最近一次心跳推上来的状态。永不为 null。 */
    VoiceStatus status();

    /**
     * 收尾。
     *
     * @return 录音落盘路径，没录则空串
     */
    String close(String reason);
}
