package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 错题。同一知识点反复答错时按 knowledge_point 合并并累加 hit_count，
 * 所以 session_id 只记最后一次命中的那场。
 */
@TableName("mistakes")
@Getter
@Setter
public class MistakeRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer sessionId;
    private String knowledgePoint;
    private String topic;
    private String question;
    private String candidateAnswer;
    private String keyPoints;
    private String severity;
    private String reviewHint;
    private Integer hitCount;
    private Boolean mastered;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
