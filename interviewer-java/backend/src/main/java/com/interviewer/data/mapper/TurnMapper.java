package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.TurnRow;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TurnMapper extends BaseMapper<TurnRow> {

    /**
     * 按 (session_id, turn_index) 覆盖。
     *
     * <p>同一轮会被写多次：转写先落一版，随后可能因打断而回填 was_interrupted。
     * 冲突时只更新会变的四个字段，speaker / started_at_ms / intent 保持首次写入的值。
     */
    @Insert("INSERT INTO turns (session_id, turn_index, speaker, text, started_at_ms, duration_ms, "
            + "intent, was_interrupted, question_index) VALUES (#{sessionId}, #{turnIndex}, "
            + "#{speaker}, #{text}, #{startedAtMs}, #{durationMs}, #{intent}, #{wasInterrupted}, "
            + "#{questionIndex}) ON CONFLICT(session_id, turn_index) DO UPDATE SET "
            + "text = excluded.text, duration_ms = excluded.duration_ms, "
            + "was_interrupted = excluded.was_interrupted, "
            + "question_index = excluded.question_index")
    int upsert(TurnRow row);

    @Select("SELECT * FROM turns WHERE session_id = #{sessionId} ORDER BY turn_index")
    List<TurnRow> listBySession(@Param("sessionId") int sessionId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM turns WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") int sessionId);
}
