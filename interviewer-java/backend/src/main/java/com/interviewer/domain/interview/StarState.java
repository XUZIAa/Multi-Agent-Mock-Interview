package com.interviewer.domain.interview;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.type.StarElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;

/** 当前行为题的 STAR 完整度。缺哪一环决定要不要下一轮 STAR_PROBE。 */
@Getter
@Setter
public class StarState {

    /** 必须钉死 JSON 名：Lombok 的 isBehavioral() 会被 Jackson 剥成 behavioral。 */
    @JsonProperty("is_behavioral")
    private boolean isBehavioral = false;

    private List<StarElement> present = new ArrayList<>();
    private int probesUsed = 0;

    @JsonIgnore
    public List<StarElement> missing() {
        return Arrays.stream(StarElement.values())
                .filter(e -> !present.contains(e))
                .toList();
    }

    public void reset(boolean behavioral) {
        this.isBehavioral = behavioral;
        this.present = new ArrayList<>();
        this.probesUsed = 0;
    }

    public void setPresent(List<StarElement> value) {
        this.present = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    /** 注入进度摘要的一行。非行为题返回空串，调用方据此决定是否加这一行。 */
    @JsonIgnore
    public String describe() {
        if (!isBehavioral) {
            return "";
        }
        String got = present.isEmpty() ? "无"
                : present.stream().map(StarElement::label).collect(Collectors.joining("、"));
        List<StarElement> lack = missing();
        String lackText = lack.isEmpty() ? "无"
                : lack.stream().map(StarElement::label).collect(Collectors.joining("、"));
        return "已覆盖：" + got + "；仍缺：" + lackText;
    }
}
