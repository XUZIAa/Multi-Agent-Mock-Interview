package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.JobRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface JobMapper extends BaseMapper<JobRow> {

    @Select("SELECT * FROM jobs ORDER BY created_at DESC LIMIT #{limit}")
    List<JobRow> listRecent(@Param("limit") int limit);
}
