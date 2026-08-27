package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一场面试。
 *
 * <p>state 是整份 InterviewState 的 JSON，每轮覆盖一次——崩了之后靠它恢复。
 * 其余列是为了列表页与统计不必解析这份大 JSON。
 */
@TableName("sessions")
@Getter
@Setter
public class SessionRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String title;
    private String status;
    private Integer personaId;
    private String personaName;
    private Integer resumeId;
    private Integer jobId;
    private Integer plannedMinutes;
    private Integer durationMs;
    private Double overallScore;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String audioPath;
    private String state;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
