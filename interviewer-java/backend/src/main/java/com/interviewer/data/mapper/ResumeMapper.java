package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.ResumeRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResumeMapper extends BaseMapper<ResumeRow> {

    @Select("SELECT * FROM resumes ORDER BY created_at DESC LIMIT #{limit}")
    List<ResumeRow> listRecent(@Param("limit") int limit);
}
