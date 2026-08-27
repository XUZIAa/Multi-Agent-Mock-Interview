package com.interviewer.domain.resume;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.Text;
import com.interviewer.core.type.GapSeverity;
import com.interviewer.core.type.InterviewPhase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 差距诊断结论。
 *
 * <p>它有三个下游：题库把 blocker 对应的题标成必问、面试计划按 phaseEmphasis 调时间
 * 分配、面试中作为「你要重点验证这些疑点」注入。
 */
@Getter
@Setter
public class GapReport {

    private int matchScore = 0;
    private String verdict = "";
    private List<SkillMatch> matches = new ArrayList<>();
    private List<SkillGap> gaps = new ArrayList<>();
    private List<String> predictedQuestions = new ArrayList<>();
    private List<String> focusSkills = new ArrayList<>();
    private Map<InterviewPhase, Integer> phaseEmphasis = new LinkedHashMap<>();

    public void setMatchScore(int value) {
        this.matchScore = Math.max(0, Math.min(100, value));
    }

    @JsonIgnore
    public List<SkillGap> blockers() {
        return gaps.stream().filter(g -> g.severity() == GapSeverity.BLOCKER).toList();
    }

    @JsonIgnore
    public String compact(int limit) {
        List<String> lines = new ArrayList<>();
        if (!verdict.isEmpty()) {
            lines.add("匹配度 " + matchScore + "/100：" + verdict);
        }
        if (!gaps.isEmpty()) {
            lines.add("已知盲区（面试中重点验证）：");
            gaps.stream().limit(6).forEach(g ->
                    lines.add("- " + g.skill() + "｜" + g.severity().label() + "｜" + g.whyGap()));
        }
        if (!focusSkills.isEmpty()) {
            lines.add("必须考到的技能点：" + String.join("、",
                    focusSkills.subList(0, Math.min(10, focusSkills.size()))));
        }
        return Text.cut(String.join("\n", lines), limit);
    }

    @JsonIgnore
    public String compact() {
        return compact(900);
    }
}
