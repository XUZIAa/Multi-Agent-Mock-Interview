package com.interviewer.data.dto;

import com.interviewer.domain.resume.ResumeProfile;

public record StoredResume(int id, String label, String filePath, ResumeProfile profile) {
}
