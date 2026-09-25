package com.interviewer.core.config;

import lombok.Getter;
import lombok.Setter;

/** 功能开关。 */
@Getter
@Setter
public class FeatureSettings {

    private boolean copilotEnabled = true;
    private boolean codingRoundEnabled = false;
    private boolean saveAudio = true;
}
