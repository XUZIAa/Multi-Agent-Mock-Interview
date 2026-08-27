package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 简历。payload 是 ResumeProfile 的 JSON，含原文全文。 */
@TableName("resumes")
@Getter
@Setter
public class ResumeRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String label;
    private String filePath;
    private String payload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
