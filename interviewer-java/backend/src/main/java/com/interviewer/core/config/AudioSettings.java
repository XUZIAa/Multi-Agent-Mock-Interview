package com.interviewer.core.config;

import lombok.Getter;

/** 音频采集与播放参数。真正干活的是 Python 语音 sidecar，握手时整份下发给它。 */
@Getter
public class AudioSettings {

    private String inputDevice = "";
    private String outputDevice = "";
    private double inputGain = 1.0;

    /** 阈值偏高会导致服务端听不到说话；宁可略灵敏，配合语义打断过滤附和声。 */
    private double vadThreshold = 0.28;

    private int silenceDurationMs = 620;
    private int prefixPaddingMs = 300;
    private boolean semanticVad = true;
    private boolean autoGain = true;

    /**
     * 上次学到的自动增益。同一台机器的麦克风增益需求稳定，记下来省去下次爬坡。
     *
     * <p>这是运行时学习值，历史值越界只能钳制、绝不能让整份配置作废。
     */
    private double learnedGain = 1.0;

    /** 起播蓄水水位。太小会断续，太大则短回复会卡在缓冲里等，听感像前半句丢了。 */
    private int playbackBufferMs = 240;

    public void setInputDevice(String value) {
        this.inputDevice = Clamp.text(value);
    }

    public void setOutputDevice(String value) {
        this.outputDevice = Clamp.text(value);
    }

    public void setInputGain(double value) {
        this.inputGain = Clamp.of(value, 0.2, 4.0);
    }

    public void setVadThreshold(double value) {
        this.vadThreshold = Clamp.of(value, 0.05, 0.95);
    }

    public void setSilenceDurationMs(int value) {
        this.silenceDurationMs = Clamp.of(value, 200, 2000);
    }

    public void setPrefixPaddingMs(int value) {
        this.prefixPaddingMs = Clamp.of(value, 0, 1000);
    }

    public void setSemanticVad(boolean value) {
        this.semanticVad = value;
    }

    public void setAutoGain(boolean value) {
        this.autoGain = value;
    }

    public void setLearnedGain(double value) {
        this.learnedGain = Clamp.of(value, 0.5, 8.0);
    }

    public void setPlaybackBufferMs(int value) {
        this.playbackBufferMs = Clamp.of(value, 60, 900);
    }
}
