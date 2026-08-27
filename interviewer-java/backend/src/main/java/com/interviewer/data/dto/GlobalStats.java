package com.interviewer.data.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 工作台的统计卡片。
 *
 * <p>分数三项可能为 null：一场都没复盘过时它们没有意义，给 0 会让界面显示「平均 0 分」。
 */
public record GlobalStats(int totalSessions, int completedSessions, int totalMinutes,
                          @Schema(nullable = true) Double bestScore,
                          @Schema(nullable = true) Double latestScore,
                          @Schema(nullable = true) Double averageScore) {
}
