package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.SessionRow;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SessionMapper extends BaseMapper<SessionRow> {

    /**
     * 列表页用的精简投影。
     *
     * <p>刻意不取 state：那是整份面试状态的 JSON，一场几十 KB，列表拉五十场会把内存和
     * 序列化时间都吃掉，而列表页一个字段都用不上。
     */
    @Select("SELECT id, title, status, persona_name, planned_minutes, duration_ms, overall_score, "
            + "started_at, ended_at, audio_path, created_at FROM sessions "
            + "ORDER BY created_at DESC LIMIT #{limit}")
    List<SessionRow> listRecent(@Param("limit") int limit);

    @Select("SELECT status FROM sessions WHERE id = #{sessionId}")
    String statusOf(@Param("sessionId") int sessionId);

    @Update("UPDATE sessions SET status = #{status}, updated_at = #{now} WHERE id = #{sessionId}")
    int updateStatus(@Param("sessionId") int sessionId, @Param("status") String status,
                     @Param("now") java.time.LocalDateTime now);

    /** 每轮落一次。duration_ms 跟着一起写，列表页不必解 state 就能显示时长。 */
    @Update("UPDATE sessions SET state = #{state}, duration_ms = #{durationMs}, "
            + "updated_at = #{now} WHERE id = #{sessionId}")
    int updateState(@Param("sessionId") int sessionId, @Param("state") String state,
                    @Param("durationMs") int durationMs,
                    @Param("now") java.time.LocalDateTime now);

    @Select("SELECT state FROM sessions WHERE id = #{sessionId}")
    String stateOf(@Param("sessionId") int sessionId);

    @Update("UPDATE sessions SET duration_ms = #{durationMs}, audio_path = #{audioPath}, "
            + "ended_at = #{now}, updated_at = #{now} WHERE id = #{sessionId}")
    int finish(@Param("sessionId") int sessionId, @Param("durationMs") int durationMs,
               @Param("audioPath") String audioPath, @Param("now") java.time.LocalDateTime now);

    /** 状态转为进行中时打开始时间戳。 */
    @Update("UPDATE sessions SET status = #{status}, started_at = #{now}, updated_at = #{now} "
            + "WHERE id = #{sessionId}")
    int markStarted(@Param("sessionId") int sessionId, @Param("status") String status,
                    @Param("now") java.time.LocalDateTime now);

    /** 状态转为完成或中止时打结束时间戳。 */
    @Update("UPDATE sessions SET status = #{status}, ended_at = #{now}, updated_at = #{now} "
            + "WHERE id = #{sessionId}")
    int markEnded(@Param("sessionId") int sessionId, @Param("status") String status,
                  @Param("now") java.time.LocalDateTime now);

    @Update("UPDATE sessions SET overall_score = #{score}, updated_at = #{now} WHERE id = #{sessionId}")
    int updateScore(@Param("sessionId") int sessionId, @Param("score") double score,
                    @Param("now") java.time.LocalDateTime now);

    /**
     * 全局统计。
     *
     * <p>一次查询拿全部聚合值，避免为一个卡片发六条 SQL。分数类聚合天然忽略 NULL，
     * 所以草稿与中止的场次不会把平均分拉低；场次数与总时长则要算上它们。
     */
    @Select("SELECT "
            + "(SELECT COUNT(*) FROM sessions) AS total_sessions, "
            + "(SELECT COUNT(*) FROM sessions WHERE status = 'completed') AS completed_sessions, "
            + "(SELECT COALESCE(SUM(duration_ms), 0) FROM sessions) AS total_ms, "
            + "(SELECT AVG(overall_score) FROM sessions) AS average_score, "
            + "(SELECT MAX(overall_score) FROM sessions) AS best_score, "
            + "(SELECT overall_score FROM sessions WHERE overall_score IS NOT NULL "
            + " ORDER BY created_at DESC LIMIT 1) AS latest_score")
    Map<String, Object> stats();

    /** 崩溃恢复用：找出状态还停在进行中的场次。 */
    @Select("SELECT id, title, status, persona_name, planned_minutes, duration_ms, "
            + "started_at, created_at FROM sessions WHERE status = #{status} "
            + "ORDER BY created_at DESC")
    List<SessionRow> listByStatus(@Param("status") String status);
}
