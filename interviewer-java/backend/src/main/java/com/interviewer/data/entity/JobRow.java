package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 岗位描述。payload 是 JobDescription 的 JSON。 */
@TableName("jobs")
@Getter
@Setter
public class JobRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String label;
    private String company;
    private String title;
    private String payload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
