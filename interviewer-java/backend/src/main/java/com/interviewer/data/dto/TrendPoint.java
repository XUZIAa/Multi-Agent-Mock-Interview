package com.interviewer.data.dto;

import java.time.LocalDateTime;

public record TrendPoint(int sessionId, LocalDateTime recordedAt, double score) {
}
