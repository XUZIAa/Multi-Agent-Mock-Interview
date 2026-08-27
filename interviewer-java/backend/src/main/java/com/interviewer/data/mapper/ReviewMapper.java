package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.ReviewRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReviewMapper extends BaseMapper<ReviewRow> {

    @Select("SELECT * FROM reviews WHERE session_id = #{sessionId} LIMIT 1")
    ReviewRow findBySession(@Param("sessionId") int sessionId);

    /** 一场一份。重新生成复盘就覆盖，created_at 保留首次生成的时间。 */
    @Insert("INSERT INTO reviews (session_id, overall_score, payload, created_at, updated_at) "
            + "VALUES (#{sessionId}, #{overallScore}, #{payload}, #{createdAt}, #{updatedAt}) "
            + "ON CONFLICT(session_id) DO UPDATE SET "
            + "overall_score = excluded.overall_score, payload = excluded.payload, "
            + "updated_at = excluded.updated_at")
    int upsert(ReviewRow row);

    /** 总分曲线。不取 payload，那是整份报告的 JSON，画线一个字段都用不上。 */
    @Select("SELECT id, session_id, overall_score, created_at, updated_at FROM reviews "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    java.util.List<ReviewRow> recentSeries(@Param("limit") int limit);
}
