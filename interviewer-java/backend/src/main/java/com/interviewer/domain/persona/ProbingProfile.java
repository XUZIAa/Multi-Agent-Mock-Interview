package com.interviewer.domain.persona;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Getter;

/** 考察偏好。各 focus 值同时参与面试计划的时间分配，改动会直接影响排期。 */
@Getter
public class ProbingProfile {

    private int divergence = 5;
    private int followUpDepth = 5;
    private int projectFocus = 7;
    private int fundamentalsFocus = 5;
    private int systemDesignFocus = 5;
    private int codingFocus = 4;
    private int behavioralFocus = 4;

    public void setDivergence(int value) {
        this.divergence = Scale.level(value);
    }

    public void setFollowUpDepth(int value) {
        this.followUpDepth = Scale.level(value);
    }

    public void setProjectFocus(int value) {
        this.projectFocus = Scale.level(value);
    }

    public void setFundamentalsFocus(int value) {
        this.fundamentalsFocus = Scale.level(value);
    }

    public void setSystemDesignFocus(int value) {
        this.systemDesignFocus = Scale.level(value);
    }

    public void setCodingFocus(int value) {
        this.codingFocus = Scale.level(value);
    }

    public void setBehavioralFocus(int value) {
        this.behavioralFocus = Scale.level(value);
    }

    @JsonIgnore
    public List<String> describe() {
        return List.of(
                "话题发散度：" + Scale.of(divergence,
                        "严格按既定题目走，不跑题",
                        "偶尔顺着回答延伸一个点",
                        "喜欢从一个点跳到相邻领域",
                        "极度发散，从一个词就能扯到完全另一个领域"),
                "追问深度：" + Scale.of(followUpDepth,
                        "问完就走，不追问",
                        "追一层确认理解",
                        "连追两三层直到触及原理",
                        "追到对方答不出来才停"));
    }

    @JsonIgnore
    public Map<String, Integer> weights() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("项目经历", projectFocus);
        map.put("基础原理", fundamentalsFocus);
        map.put("系统设计", systemDesignFocus);
        map.put("编码实现", codingFocus);
        map.put("行为与协作", behavioralFocus);
        return map;
    }

    @JsonIgnore
    public String focusLine() {
        String ranked = weights().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .filter(e -> e.getValue() > 0)
                .map(e -> e.getKey() + "(" + e.getValue() + "/10)")
                .collect(Collectors.joining("，"));
        return "考察配比（数值越高越要多花时间）：" + ranked;
    }
}
