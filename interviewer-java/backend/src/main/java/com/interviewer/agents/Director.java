package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.error.ProviderResponseException;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.Depth;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.bank.BankQuestion;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.QuestionRecord;
import com.interviewer.domain.turn.DirectorDecision;
import com.interviewer.domain.turn.TurnPlan;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 面试的大脑。它不说话，只在 policy 划定的边界内决定下一步。
 *
 * <p>模型给的每一项都要过一遍闸门：intent 不在允许集里就回落到第一项，题号不在候选集里就
 * 按优先级取第一个，说要打断但额度用完了就当没说。规则是最终裁决者。
 */
@Component
public class Director extends Agent {

    private static final Logger log = LoggerFactory.getLogger(Director.class);

    public Director(LlmRouter router) {
        super(router);
    }

    @Override
    protected String role() {
        return Providers.ROLE_DIRECTOR;
    }

    /** 导演的原始输出。reasoning 收下但不用，留着是为了让模型有地方写思路而不污染 brief。 */
    record Raw(@JsonProperty("intent") String intent,
               @JsonProperty("brief") String brief,
               @JsonProperty("target_skill") String targetSkill,
               @JsonProperty("chosen_question_id") Integer chosenQuestionId,
               @JsonProperty("is_personality") boolean isPersonality,
               @JsonProperty("should_advance_phase") boolean shouldAdvancePhase,
               @JsonProperty("should_interrupt") boolean shouldInterrupt,
               @JsonProperty("answer_quality") Double answerQuality,
               @JsonProperty("answer_summary") String answerSummary,
               @JsonProperty("covered_skills") List<String> coveredSkills,
               @JsonProperty("dimension_deltas") Map<String, Double> dimensionDeltas,
               @JsonProperty("reasoning") String reasoning) {

        Raw(String intent, String brief, String targetSkill, Integer chosenQuestionId,
            boolean isPersonality, boolean shouldAdvancePhase, boolean shouldInterrupt,
            Double answerQuality, String answerSummary, List<String> coveredSkills,
            Map<String, Double> dimensionDeltas, String reasoning) {
            this.intent = Text.safe(intent);
            this.brief = Text.safe(brief);
            this.targetSkill = Text.safe(targetSkill);
            this.chosenQuestionId = chosenQuestionId;
            this.isPersonality = isPersonality;
            this.shouldAdvancePhase = shouldAdvancePhase;
            this.shouldInterrupt = shouldInterrupt;
            this.answerQuality = answerQuality;
            this.answerSummary = Text.safe(answerSummary);
            this.coveredSkills = coveredSkills == null ? List.of() : List.copyOf(coveredSkills);
            this.dimensionDeltas = dimensionDeltas == null ? Map.of() : Map.copyOf(dimensionDeltas);
            this.reasoning = Text.safe(reasoning);
        }
    }

    public DirectorDecision decide(InterviewState state, TurnPlan plan) {
        if (plan.allowedIntents().isEmpty()) {
            throw new ProviderResponseException("没有可用的 intent，状态机配置错误");
        }

        Raw raw = client().structured(
                List.of(new SystemMessage(Prompts.DIRECTOR_SYSTEM),
                        new UserMessage(Prompts.directorUser(buildInput(state, plan)))),
                Raw.class, 0.5, 1400, 1);

        TurnIntent intent = resolveIntent(raw.intent(), plan);
        BankQuestion chosen = resolveQuestion(raw.chosenQuestionId(), intent, plan);

        // 说要开新题却没题可开，只能退回追问或切阶段
        if (intent.opensNewQuestion() && chosen == null) {
            if (plan.followUpAllowed() && plan.allowedIntents().contains(TurnIntent.FOLLOW_UP)) {
                intent = TurnIntent.FOLLOW_UP;
            } else if (plan.allowedIntents().contains(TurnIntent.TRANSITION)) {
                intent = TurnIntent.TRANSITION;
            } else {
                intent = plan.allowedIntents().get(0);
            }
        }

        String brief = Text.trim(raw.brief(), 150);
        if (chosen != null) {
            // 下发用不含 jd_ref 的版本：JD 原文写法是「熟悉 XXX」，
            // 直接给语音会被当成候选人的自述念出来
            brief = Text.trim(chosen.briefForVoice(), 200);
        }
        if (brief.isEmpty()) {
            throw new ProviderResponseException("导演未给出可执行的 brief");
        }

        String targetSkill = Text.trim(raw.targetSkill(), 60);
        String domain = "";
        int depth = 1;
        if (chosen != null) {
            targetSkill = chosen.skill();
            domain = chosen.domain();
            depth = chosen.depth();
        } else if (intent.isProbe()) {
            QuestionRecord current = state.currentQuestion();
            if (current != null) {
                targetSkill = targetSkill.isEmpty() ? current.getTargetSkill() : targetSkill;
                domain = current.getDomain();
                // 只有边界测试才加深一层，普通追问停在同层
                depth = Math.min(Depth.MAX,
                        current.getDepth() + (intent == TurnIntent.BOUNDARY_TEST ? 1 : 0));
            }
        }

        DirectorDecision decision = new DirectorDecision(
                intent, brief, targetSkill, domain, depth, chosen,
                raw.isPersonality() || plan.forcePersonality(),
                raw.shouldAdvancePhase(),
                raw.shouldInterrupt() && plan.interruptAllowed(),
                raw.answerQuality() == null ? null : Text.clamp01(raw.answerQuality()),
                Text.trim(raw.answerSummary(), 120),
                cleanSkills(raw.coveredSkills()),
                coerceDimensions(raw.dimensionDeltas()));

        log.info("导演决策 intent={} qid={} skill={} depth={} advance={} interrupt={}",
                decision.intent().value(), chosen == null ? null : chosen.id(),
                decision.targetSkill(), decision.depth(),
                decision.shouldAdvancePhase(), decision.shouldInterrupt());
        return decision;
    }

    private static Prompts.DirectorInput buildInput(InterviewState state, TurnPlan plan) {
        QuestionRecord current = state.currentQuestion();

        // 期望信号取自当前这道题（回题库拿要点）。没有关联时退到候选里的下一题，
        // 那道多半就是接下来要考的
        List<String> expected = List.of();
        if (current != null && current.getBankQuestionId() != null) {
            BankQuestion bankQ = state.getBank().byId(current.getBankQuestionId());
            if (bankQ != null) {
                expected = bankQ.expectedSignals();
            }
        }
        if (expected.isEmpty() && !plan.candidates().isEmpty()) {
            expected = plan.candidates().get(0).expectedSignals();
        }

        // 历史评分：追问时用当前技能点，换题时用下一题的
        String skillForHistory = current == null ? "" : current.getTargetSkill();
        if (!Text.notBlank(skillForHistory) && !plan.candidates().isEmpty()) {
            skillForHistory = plan.candidates().get(0).skill();
        }
        List<Prompts.SkillScore> history = new ArrayList<>();
        state.recentSkillScores(skillForHistory, 3)
                .forEach(m -> history.add(new Prompts.SkillScore(m.skill(), m.score())));

        List<String> intents = new ArrayList<>();
        plan.allowedIntents().forEach(i -> intents.add(i.value()));

        return new Prompts.DirectorInput(
                state.getPersona().styleBlock(), state.digest(), state.contextBlock(),
                lastAnswer(state), intents, plan.candidateBlock(), plan.phaseHint(),
                plan.interruptAllowed(), plan.followUpAllowed(), plan.forcePersonality(),
                expected.isEmpty() ? null : expected,
                history.isEmpty() ? null : history);
    }

    /** 当前题已累积的答案优先；还没归到题上时退回最后一次候选人发言。 */
    private static String lastAnswer(InterviewState state) {
        QuestionRecord current = state.currentQuestion();
        if (current != null && Text.notBlank(current.getAnswerText())) {
            return current.getAnswerText().strip();
        }
        var last = state.lastCandidateTurn();
        return last == null ? "" : last.getText();
    }

    private static TurnIntent resolveIntent(String value, TurnPlan plan) {
        TurnIntent intent = com.interviewer.core.type.Labeled
                .find(TurnIntent.class, value).orElse(null);
        if (intent == null) {
            log.warn("导演给出未知 intent: {}", value);
            return plan.allowedIntents().get(0);
        }
        if (!plan.allowedIntents().contains(intent)) {
            log.warn("导演越界 intent={}，按边界回落", intent.value());
            return plan.allowedIntents().get(0);
        }
        return intent;
    }

    private static BankQuestion resolveQuestion(Integer questionId, TurnIntent intent,
                                                TurnPlan plan) {
        if (!intent.opensNewQuestion() || plan.candidates().isEmpty()) {
            return null;
        }
        if (questionId != null) {
            for (BankQuestion q : plan.candidates()) {
                if (q.id() == questionId) {
                    return q;
                }
            }
            log.warn("导演选了不在候选集里的题目 id={}，按优先级取第一个", questionId);
        }
        return plan.candidates().get(0);
    }

    private static List<String> cleanSkills(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String skill : raw) {
            if (Text.notBlank(skill) && out.size() < 6) {
                out.add(Text.trim(skill, 40));
            }
        }
        return List.copyOf(out);
    }

    /** 认不出的维度名直接丢掉，不要让它变成一个假维度进到实时分里。 */
    private static Map<ScoreDimension, Double> coerceDimensions(Map<String, Double> raw) {
        Map<ScoreDimension, Double> out = new EnumMap<>(ScoreDimension.class);
        raw.forEach((key, value) -> {
            ScoreDimension dim = ScoreDimension.of(key);
            if (dim != null && value != null) {
                out.put(dim, Text.clamp(value, 0.0, 100.0));
            }
        });
        return out;
    }
}
