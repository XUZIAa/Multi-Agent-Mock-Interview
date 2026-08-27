package com.interviewer.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 人设。payload 是整份 PersonaContract 的 JSON，name/archetype 提出来供列表与去重用。 */
@TableName("personas")
@Getter
@Setter
public class PersonaRow {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String name;
    private String archetype;

    @TableField("is_builtin")
    private Boolean isBuiltin;

    private Integer usageCount;
    private String payload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
