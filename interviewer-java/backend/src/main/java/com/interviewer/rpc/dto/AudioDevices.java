package com.interviewer.rpc.dto;

import java.util.List;

public record AudioDevices(List<AudioDeviceOption> inputs, List<AudioDeviceOption> outputs) {

    public static final AudioDevices EMPTY = new AudioDevices(List.of(), List.of());
}
