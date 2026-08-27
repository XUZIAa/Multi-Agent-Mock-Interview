package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 一次发言。(session_id, turn_index) 唯一，靠它做 upsert。 */
@TableName("turns")
@Getter
@Setter
public class TurnRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer sessionId;
    private Integer turnIndex;
    private String speaker;
    private String text;
    private Integer startedAtMs;
    private Integer durationMs;
    private String intent;
    private Boolean wasInterrupted;
    private Integer questionIndex;
}
