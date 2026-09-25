package com.interviewer.rpc.dto;

import java.util.List;

public record ServerInfo(String name, String version, List<String> eventNames, int subscribers) {
}
