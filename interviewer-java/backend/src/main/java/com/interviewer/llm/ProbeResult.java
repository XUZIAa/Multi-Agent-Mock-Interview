package com.interviewer.llm;

/**
 * 连通性探测结果。detail 是给用户看的人话，不是服务端原文。
 */
public record ProbeResult(boolean ok, String detail, long latencyMs) {

    public static ProbeResult fail(String detail) {
        return new ProbeResult(false, detail, 0);
    }
}
