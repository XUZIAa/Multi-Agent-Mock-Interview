package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.GapReportRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GapReportMapper extends BaseMapper<GapReportRow> {

    @Select("SELECT * FROM gap_reports WHERE resume_id = #{resumeId} AND job_id = #{jobId} "
            + "ORDER BY created_at DESC LIMIT 1")
    GapReportRow findByPair(@Param("resumeId") int resumeId, @Param("jobId") int jobId);

    /**
     * 同一对简历与岗位只留一份诊断。
     *
     * <p>没有唯一约束可依赖（老库的 ix_gap_pair 只是普通索引），所以先删后插，
     * 由调用方放在同一个事务里。
     */
    @Insert("INSERT INTO gap_reports (resume_id, job_id, match_score, payload, created_at, updated_at) "
            + "VALUES (#{resumeId}, #{jobId}, #{matchScore}, #{payload}, #{createdAt}, #{updatedAt})")
    int insertRow(GapReportRow row);

    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM gap_reports WHERE resume_id = #{resumeId} AND job_id = #{jobId}")
    int deleteByPair(@Param("resumeId") int resumeId, @Param("jobId") int jobId);
}
