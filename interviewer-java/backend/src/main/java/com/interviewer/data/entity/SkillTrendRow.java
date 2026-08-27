package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 每场面试每个维度落一行，成长曲线直接按此聚合，避免解析大 JSON。 */
@TableName("skill_trends")
@Getter
@Setter
public class SkillTrendRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer sessionId;
    private String dimension;
    private Double score;
    private LocalDateTime recordedAt;
}
