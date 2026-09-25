package com.interviewer.data.dto;

import com.interviewer.domain.resume.JobDescription;

public record StoredJob(int id, String label, JobDescription job) {
}
