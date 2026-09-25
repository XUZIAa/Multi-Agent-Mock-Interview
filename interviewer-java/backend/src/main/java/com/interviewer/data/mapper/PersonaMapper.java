package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.PersonaRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PersonaMapper extends BaseMapper<PersonaRow> {

    /** 内置的排前面，其余按创建时间倒序——用户自己建的更可能是刚要用的。 */
    @Select("SELECT * FROM personas ORDER BY is_builtin DESC, created_at DESC")
    List<PersonaRow> listAll();

    @Select("SELECT * FROM personas WHERE name = #{name} LIMIT 1")
    PersonaRow findByName(@Param("name") String name);

    /** 判重用。取名时要避开已存在的名字，不必把整行读出来。 */
    @Select("SELECT name FROM personas")
    List<String> allNames();

    @Select("SELECT COUNT(*) FROM personas")
    int count();
}
