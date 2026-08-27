package com.interviewer.core.error;

public class AudioDeviceException extends InterviewerException {

    public AudioDeviceException(String detail) {
        super(detail);
    }

    @Override
    protected String defaultUserMessage() {
        return "音频设备不可用，请检查麦克风与扬声器";
    }
}
