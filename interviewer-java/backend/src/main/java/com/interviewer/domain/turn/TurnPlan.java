package com.interviewer.domain.turn;

import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.bank.BankQuestion;
import com.interviewer.domain.bank.DepthAction;
import java.util.List;

/**
 * 本轮的硬边界。由确定性规则算出，导演只能在这个范围内做选择。
 *
 * <p>越界的 intent 会被回落到 allowedIntents 的第一项，不在 candidates 里的题号会被
 * 忽略。模型的自由度到此为止。
 */
public record TurnPlan(List<TurnIntent> allowedIntents, List<BankQuestion> candidates,
                       DepthAction depthAction, String phaseHint, boolean interruptAllowed,
                       boolean followUpAllowed, boolean forcePersonality, boolean mustClose,
                       String preferDomain, List<String> avoidDomains) {

    public String candidateBlock() {
        if (candidates.isEmpty()) {
            return "（题库中当前没有可用的新问题，只能追问、过渡或收尾）";
        }
        StringBuilder sb = new StringBuilder(
                "可选的新问题（新问题必须从这里选，把编号填进 chosen_question_id）：");
        candidates.forEach(q -> sb.append("\n- ").append(q.oneLine()));
        return sb.toString();
    }

    /** 时间到收尾线时的唯一形态：只允许结束，没有候选题。 */
    public static TurnPlan closing() {
        return new TurnPlan(List.of(TurnIntent.CLOSE), List.of(), null,
                "时间已到收尾线，本轮必须结束面试。",
                false, false, false, true, null, List.of());
    }
}
