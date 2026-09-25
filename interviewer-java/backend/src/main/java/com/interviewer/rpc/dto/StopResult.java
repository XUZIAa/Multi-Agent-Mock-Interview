package com.interviewer.rpc.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 不回传完整 InterviewState，前端要的只是收尾去哪。 */
// 可空必须写进文档：前端按 session_id === null 判断有没有可收尾的会话
public record StopResult(@Schema(nullable = true) Integer sessionId, boolean reviewable,
                         long elapsedMs) {

    public static final StopResult NONE = new StopResult(null, false, 0);
}
