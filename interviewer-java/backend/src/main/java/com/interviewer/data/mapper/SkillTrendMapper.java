package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.SkillTrendRow;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillTrendMapper extends BaseMapper<SkillTrendRow> {

    /** 同一场同一维度只留一个点，重新生成复盘就覆盖。 */
    @Insert("INSERT INTO skill_trends (session_id, dimension, score, recorded_at) "
            + "VALUES (#{sessionId}, #{dimension}, #{score}, #{recordedAt}) "
            + "ON CONFLICT(session_id, dimension) DO UPDATE SET "
            + "score = excluded.score, recorded_at = excluded.recorded_at")
    int upsert(SkillTrendRow row);

    /**
     * 某个维度的历史序列。
     *
     * <p>先按时间倒序取最近 limit 条，再交给调用方翻正——直接正序 LIMIT 会取到最早的几场。
     */
    @Select("SELECT * FROM skill_trends WHERE dimension = #{dimension} "
            + "ORDER BY recorded_at DESC, id DESC LIMIT #{limit}")
    List<SkillTrendRow> recentByDimension(@Param("dimension") String dimension,
                                          @Param("limit") int limit);

    /**
     * 跨维度取最近若干行。
     *
     * <p>调用方按 limit×维度数 传进来，再自行按维度分组。这样一次查询就够，
     * 代价是某场缺维度时会多取到几场——与 Python 版同样的取舍。
     */
    @Select("SELECT * FROM skill_trends ORDER BY recorded_at DESC, id DESC LIMIT #{limit}")
    List<SkillTrendRow> recentAll(@Param("limit") int limit);

    @Select("SELECT DISTINCT dimension FROM skill_trends")
    List<String> dimensions();
}
