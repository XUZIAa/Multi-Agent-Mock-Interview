package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 差距诊断。按 (resume_id, job_id) 复用，重算才覆盖。 */
@TableName("gap_reports")
@Getter
@Setter
public class GapReportRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer resumeId;
    private Integer jobId;
    private Integer matchScore;
    private String payload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
