package com.interviewer.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewer.data.entity.MistakeRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MistakeMapper extends BaseMapper<MistakeRow> {

    /**
     * 按知识点找未掌握的那条。
     *
     * <p>已掌握的不参与合并：那意味着用户认为自己已经补上了，再答错应该重新开一条，
     * 而不是把旧记录的 hit_count 累上去。
     */
    @Select("SELECT * FROM mistakes WHERE knowledge_point = #{point} AND mastered = 0 LIMIT 1")
    MistakeRow findPendingByPoint(@Param("point") String point);

    /** 未掌握的排前面，命中多的靠前，同命中数按最近答错的靠前。 */
    @Select("<script>SELECT * FROM mistakes"
            + "<where>"
            + "<if test='!includeMastered'> mastered = 0 </if>"
            + "<if test=\"topic != null and topic != ''\"> AND topic = #{topic} </if>"
            + "</where>"
            + " ORDER BY mastered, hit_count DESC, last_seen_at DESC LIMIT #{limit}</script>")
    List<MistakeRow> listFiltered(@Param("includeMastered") boolean includeMastered,
                                  @Param("topic") String topic,
                                  @Param("limit") int limit);

    @Select("SELECT DISTINCT topic FROM mistakes WHERE topic != '' ORDER BY topic")
    List<String> topics();

    @Select("SELECT COUNT(*) FROM mistakes WHERE mastered = #{mastered}")
    int countByMastered(@Param("mastered") boolean mastered);

    @Update("UPDATE mistakes SET mastered = #{mastered}, updated_at = #{now} WHERE id = #{id}")
    int setMastered(@Param("id") int id, @Param("mastered") boolean mastered,
                    @Param("now") LocalDateTime now);
}
