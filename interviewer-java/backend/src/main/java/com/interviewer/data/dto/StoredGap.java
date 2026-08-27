package com.interviewer.data.dto;

import com.interviewer.domain.resume.GapReport;

public record StoredGap(int id, int resumeId, int jobId, GapReport report) {
}
