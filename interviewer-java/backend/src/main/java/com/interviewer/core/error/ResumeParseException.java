package com.interviewer.core.error;

public class ResumeParseException extends InterviewerException {

    public ResumeParseException(String detail) {
        super(detail);
    }

    public ResumeParseException(String detail, Throwable cause) {
        super(detail, null, cause);
    }

    @Override
    protected String defaultUserMessage() {
        return "简历解析失败，请确认文件格式为 PDF / DOCX / TXT";
    }
}
