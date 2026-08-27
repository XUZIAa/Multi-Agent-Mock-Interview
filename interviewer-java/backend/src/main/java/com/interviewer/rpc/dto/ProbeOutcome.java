package com.interviewer.rpc.dto;

import com.interviewer.llm.ProbeResult;

public record ProbeOutcome(boolean ok, String detail, long latencyMs) {

    public static ProbeOutcome of(ProbeResult result) {
        return new ProbeOutcome(result.ok(), result.detail(), result.latencyMs());
    }
}
