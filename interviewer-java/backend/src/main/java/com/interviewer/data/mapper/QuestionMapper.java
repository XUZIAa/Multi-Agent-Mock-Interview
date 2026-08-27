package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.QuestionRow;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuestionMapper extends BaseMapper<QuestionRow> {

    /**
     * 按 (session_id, question_index) 覆盖。
     *
     * <p>冲突时只更新答案与质量分：phase / intent / brief / target_skill 是开题那一刻
     * 定下来的权威值，后续回填不该动它们。收尾时的整体对齐也走这条，所以更不能全字段覆盖。
     */
    @Insert("INSERT INTO questions (session_id, question_index, phase, intent, brief, target_skill, "
            + "spoken_text, answer_text, asked_at_ms, follow_up_depth, quality) VALUES "
            + "(#{sessionId}, #{questionIndex}, #{phase}, #{intent}, #{brief}, #{targetSkill}, "
            + "#{spokenText}, #{answerText}, #{askedAtMs}, #{followUpDepth}, #{quality}) "
            + "ON CONFLICT(session_id, question_index) DO UPDATE SET "
            + "spoken_text = excluded.spoken_text, answer_text = excluded.answer_text, "
            + "quality = excluded.quality")
    int upsert(QuestionRow row);

    @Select("SELECT * FROM questions WHERE session_id = #{sessionId} ORDER BY question_index")
    List<QuestionRow> listBySession(@Param("sessionId") int sessionId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM questions WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") int sessionId);
}
