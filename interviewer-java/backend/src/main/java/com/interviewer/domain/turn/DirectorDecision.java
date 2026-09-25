package com.interviewer.domain.turn;

import com.interviewer.core.type.ScoreDimension;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.bank.BankQuestion;
import java.util.List;
import java.util.Map;

/**
 * 导演的一条指令。面试官只执行 brief，不知道其余字段。
 *
 * <p>不出网络边界，纯内部结构。改判由 {@code policy} 做——规则是最终裁决者，
 * 所以这里给出的两个改判方法就是规则唯一能动的东西。
 */
public record DirectorDecision(TurnIntent intent, String brief, String targetSkill, String domain,
                               int depth, BankQuestion chosenQuestion, boolean isPersonality,
                               boolean shouldAdvancePhase, boolean shouldInterrupt,
                               Double answerQuality, String answerSummary,
                               List<String> coveredSkills,
                               Map<ScoreDimension, Double> dimensionDeltas) {

    /** 无题可换时，收掉当前话题切下一个环节。 */
    public DirectorDecision asTransition(String newBrief) {
        return new DirectorDecision(TurnIntent.TRANSITION, newBrief, targetSkill, domain, depth,
                null, isPersonality, shouldAdvancePhase, shouldInterrupt,
                answerQuality, answerSummary, coveredSkills, dimensionDeltas);
    }

    /** 导演还想在答不上来的点上纠缠时，规则把它拉到一道新题上。 */
    public DirectorDecision asNewQuestion(BankQuestion question) {
        return new DirectorDecision(TurnIntent.ASK_NEW, question.briefForDirector(),
                question.skill(), question.domain(), question.depth(), question,
                isPersonality, shouldAdvancePhase, shouldInterrupt,
                answerQuality, answerSummary, coveredSkills, dimensionDeltas);
    }
}
