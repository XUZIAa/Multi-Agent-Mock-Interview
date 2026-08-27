package com.interviewer.domain.resume;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.Text;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 从简历原文抽出的结构化画像，后续所有个性化都基于它。 */
@Getter
@Setter
public class ResumeProfile {

    private String sourceName = "";
    private String rawText = "";
    private String candidateName = "";
    private double yearsOfExperience = 0.0;
    private String currentTitle = "";
    private List<String> skills = new ArrayList<>();
    private List<ProjectHighlight> projects = new ArrayList<>();
    private String education = "";
    private List<String> selfClaims = new ArrayList<>();

    @JsonIgnore
    public boolean isEmpty() {
        return rawText.isBlank();
    }

    /** 给模型的压缩画像，避免每轮塞全文。 */
    @JsonIgnore
    public String compact(int limit) {
        List<String> parts = new ArrayList<>();
        if (!candidateName.isEmpty()) {
            parts.add("候选人：" + candidateName);
        }
        if (!currentTitle.isEmpty()) {
            parts.add("当前职位：" + currentTitle);
        }
        if (yearsOfExperience != 0.0) {
            parts.add("经验年限：" + trimNumber(yearsOfExperience) + " 年");
        }
        if (!skills.isEmpty()) {
            parts.add("技能栈：" + String.join("、", head(skills, 24)));
        }
        for (ProjectHighlight proj : head(projects, 4)) {
            String stack = proj.stack().isEmpty() ? ""
                    : "（" + String.join("/", head(proj.stack(), 6)) + "）";
            String impact = proj.impact().isEmpty() ? "" : " 产出：" + proj.impact();
            parts.add("项目「" + proj.name() + "」" + stack
                    + " 角色：" + (proj.role().isEmpty() ? "未说明" : proj.role()) + "。"
                    + proj.summary() + impact);
        }
        if (!education.isEmpty()) {
            parts.add("教育：" + education);
        }
        return Text.cut(String.join("\n", parts), limit);
    }

    @JsonIgnore
    public String compact() {
        return compact(1600);
    }

    /** 对应 Python 的 %g：整数不带小数点，3.5 年照样是 3.5。 */
    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return Text.fmt("%s", value);
    }

    private static <T> List<T> head(List<T> list, int limit) {
        return list.size() <= limit ? list : list.subList(0, limit);
    }
}
