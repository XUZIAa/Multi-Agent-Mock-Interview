package com.interviewer.data.dto;

import com.interviewer.core.type.SessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 列表页用的会话摘要。不含 state，那是几十 KB 的大 JSON。 */
public record SessionSummary(int id, String title, SessionStatus status, String personaName,
                             LocalDateTime createdAt, int durationMs,
                             // 还没复盘就没有分数，给 0 会在列表里显示「0 分」
                             @Schema(nullable = true) Double overallScore,
                             int plannedMinutes) {
}
