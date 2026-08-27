package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 复盘报告。一场一份，session_id 唯一。 */
@TableName("reviews")
@Getter
@Setter
public class ReviewRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer sessionId;
    private Double overallScore;
    private String payload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
