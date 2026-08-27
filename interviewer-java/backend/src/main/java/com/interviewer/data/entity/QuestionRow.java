package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 一道问出去的题。(session_id, question_index) 唯一，答案会随候选人发言累加后覆盖。 */
@TableName("questions")
@Getter
@Setter
public class QuestionRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer sessionId;
    private Integer questionIndex;
    private String phase;
    private String intent;
    private String brief;
    private String targetSkill;
    private String spokenText;
    private String answerText;
    private Integer askedAtMs;
    private Integer followUpDepth;
    private Double quality;
}
