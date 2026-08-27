package com.interviewer.voice;

/**
 * 语音侧按心跳推上来的状态。
 *
 * <p>Python 单进程版里这些是同步属性读（{@code client.player.pending_ms}）。跨进程后
 * 同步读会变成一次 RPC 往返，而读它的地方是每 100ms 一跳的心跳与打断判定，
 * 所以改成语音侧主动推、编排层缓存最新值。
 *
 * @param connected      链路是否连通
 * @param responding     模型是否还在生成
 * @param pendingMs      播放缓冲里还剩多少毫秒音频
 * @param candidateLevel 候选人侧瞬时电平 0~1
 * @param voiceLevel     面试官侧瞬时电平 0~1
 * @param autoGain       语音侧学到的麦克风增益，收尾时记回配置
 */
public record VoiceStatus(boolean connected, boolean responding, long pendingMs,
                          double candidateLevel, double voiceLevel, double autoGain) {

    public static VoiceStatus idle() {
        return new VoiceStatus(false, false, 0, 0, 0, 1.0);
    }

    /** 面试官是否正在出声。以「还在生成或缓冲未放完」为界，不用瞬时电平。 */
    public boolean voicing() {
        return responding || pendingMs > 0;
    }
}
