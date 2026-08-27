package com.interviewer.rpc.dto;

/**
 * 只传 session_id：InterviewState 有几十个字段且内含整份题库，让它在前后端往返一遍
 * 纯属浪费，后端自己从库里取更可靠。
 */
public record StartInterviewBody(int sessionId) {
}
