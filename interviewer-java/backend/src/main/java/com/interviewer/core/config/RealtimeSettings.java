package com.interviewer.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.provider.RealtimeProvider;
import lombok.Getter;

/** 实时语音的供应商、模型与音色。 */
@Getter
public class RealtimeSettings {

    private String provider = "qwen_omni";
    private String model = "";
    private String voice = "";
    private double temperature = 0.85;

    public void setProvider(String value) {
        this.provider = Clamp.text(value).isEmpty() ? "qwen_omni" : Clamp.text(value);
    }

    public void setModel(String value) {
        this.model = Clamp.text(value);
    }

    public void setVoice(String value) {
        this.voice = Clamp.text(value);
    }

    public void setTemperature(double value) {
        this.temperature = Clamp.of(value, 0.1, 1.5);
    }

    @JsonIgnore
    public RealtimeProvider catalog() {
        return Providers.realtime(provider);
    }

    @JsonIgnore
    public String resolvedModel() {
        return model.isEmpty() ? catalog().defaultModel() : model;
    }

    @JsonIgnore
    public String resolvedVoice() {
        return voice.isEmpty() ? catalog().defaultVoice() : voice;
    }
}
