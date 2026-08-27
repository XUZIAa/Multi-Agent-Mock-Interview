package com.interviewer.domain.company;

import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.ScoreDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一类公司的真实考察偏好。出题、追问口径、评分权重都从这里派生。
 *
 * <p>七个 0~10 的配比值决定「花时间在哪」，scoreBias 决定「同样的表现算几分」——
 * 大厂看深度、制造业看稳定，这两件事必须分开表达。
 */
public record CompanyProfile(CompanyTier tier, String summary, String interviewStyle,
                             List<String> hotTopics, List<String> coldTopics, String jdFlavor,
                             int algorithm, int systemDesign, int fundamentals, int project,
                             int process, int cost, int stability,
                             Map<ScoreDimension, Double> scoreBias) {

    public String label() {
        return tier.label();
    }

    /** 按配比从高到低排。并列时保持声明顺序，这样同类公司的口径读起来稳定。 */
    public String emphasisLine() {
        List<Map.Entry<String, Integer>> pairs = new ArrayList<>(List.of(
                Map.entry("算法与数据结构", algorithm),
                Map.entry("系统设计", systemDesign),
                Map.entry("语言与框架原理", fundamentals),
                Map.entry("项目落地经验", project),
                Map.entry("流程与规范", process),
                Map.entry("成本与效率意识", cost),
                Map.entry("稳定性与容错", stability)));
        pairs.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        return pairs.stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> e.getKey() + "(" + e.getValue() + "/10)")
                .collect(Collectors.joining("、"));
    }

    public String guidanceBlock() {
        List<String> lines = new ArrayList<>();
        lines.add("【公司类型】" + tier.label() + "——" + summary);
        lines.add("【这类公司的面试口径】" + interviewStyle);
        lines.add("【必须覆盖的话题】" + String.join("、", hotTopics));
        lines.add("【考察配比】" + emphasisLine());
        if (!coldTopics.isEmpty()) {
            lines.add("【不要浪费时间的话题】" + String.join("、", coldTopics));
        }
        return String.join("\n", lines);
    }

    /** 口径的第一句。级别预期那段只引用它，全文太长会把提示词撑爆。 */
    public String styleHeadline() {
        int cut = interviewStyle.indexOf('；');
        return cut < 0 ? interviewStyle : interviewStyle.substring(0, cut);
    }
}
