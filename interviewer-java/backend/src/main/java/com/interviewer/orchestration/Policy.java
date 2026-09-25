package com.interviewer.orchestration;

import com.interviewer.core.Text;
import com.interviewer.core.config.OrchestrationSettings;
import com.interviewer.core.type.Depth;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.QuestionSource;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.bank.BankQuestion;
import com.interviewer.domain.bank.DepthAction;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.QuestionRecord;
import com.interviewer.domain.turn.DirectorDecision;
import com.interviewer.domain.turn.TurnPlan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回合边界。全部是确定性规则，一次模型都不调。
 *
 * <p>这是「模型只在规则划定的范围内做选择」的那一半：允许哪些 intent、能挑哪些题、
 * 该加深还是换领域，都在这里算好。导演越界会被回落。
 */
public final class Policy {

    private static final Logger log = LoggerFactory.getLogger(Policy.class);

    /** 每个阶段允许的意图。顺序有意义：回落时取第一个。 */
    private static final Map<InterviewPhase, List<TurnIntent>> PHASE_INTENTS = phaseIntents();

    /** 每个阶段该从哪些来源挑题。空集表示这个阶段不出题。 */
    private static final Map<InterviewPhase, List<QuestionSource>> PHASE_SOURCES = phaseSources();

    /** 剩余时间不足总时长这个比例时，必问项优先。 */
    private static final double TIME_PRESSURE_RATIO = 0.3;

    /** 上一题答得太差时不穿插性格题：那会像在给他台阶下。 */
    private static final double PERSONALITY_QUALITY_GATE = 0.55;

    private Policy() {
    }

    public static TurnPlan planTurn(InterviewState state, OrchestrationSettings settings) {
        if (state.mustClose()) {
            return TurnPlan.closing();
        }

        QuestionRecord current = state.currentQuestion();
        DepthAction depthAction = state.getLastDepthAction();
        boolean hasAnswer = current != null && Text.notBlank(current.getAnswerText());

        boolean followUpAllowed = hasAnswer && state.canFollowUp(settings.getMaxFollowUpDepth());
        boolean interruptAllowed = state.canInterrupt(settings.getInterruptBudgetPerPhase());

        List<String> avoid = List.of();
        String prefer = null;
        if (depthAction != null && depthAction.leavesTopic()) {
            if (Text.notBlank(state.getCurrentDomain())) {
                avoid = List.of(state.getCurrentDomain());
            }
            // 已经判定该走了，就不能再给追问的机会
            followUpAllowed = false;
        } else if (depthAction == DepthAction.DEEPEN || depthAction == DepthAction.SIDESTEP) {
            prefer = Text.notBlank(state.getCurrentDomain()) ? state.getCurrentDomain() : null;
        }

        List<TurnIntent> intents = new ArrayList<>(PHASE_INTENTS.get(state.getPhase()));
        if (state.getPhase() == InterviewPhase.WARMUP && current != null && hasAnswer) {
            // 暖场首答只允许顺着自我介绍追问，不能跳到题库的预设问题
            intents = new ArrayList<>(List.of(TurnIntent.FOLLOW_UP));
        }

        if (!followUpAllowed) {
            intents.removeIf(TurnIntent::isProbe);
        }
        if (depthAction == DepthAction.SIDESTEP) {
            intents.remove(TurnIntent.BOUNDARY_TEST);
        }
        if (depthAction == DepthAction.ABANDON) {
            intents.remove(TurnIntent.PRESSURE);
            intents.remove(TurnIntent.BOUNDARY_TEST);
        }
        if (state.getPhase() == InterviewPhase.BEHAVIORAL && state.getStar().missing().isEmpty()) {
            intents.remove(TurnIntent.STAR_PROBE);
        }
        if (state.getPersona().getPressure().getChallengeFrequency() < 4) {
            intents.remove(TurnIntent.PRESSURE);
        }
        if (!hasAnswer) {
            // 他还没说话，无从「简短回应」
            intents.remove(TurnIntent.ACKNOWLEDGE);
        }

        boolean forcePersonality = state.personalityProbeDue()
                && lastQuality(state) >= PERSONALITY_QUALITY_GATE;
        List<QuestionSource> sources = PHASE_SOURCES.get(state.getPhase());
        if (forcePersonality) {
            sources = List.of(QuestionSource.BEHAVIORAL);
        }

        List<BankQuestion> candidates = List.of();
        if (state.getPhase() != InterviewPhase.CANDIDATE_QA) {
            candidates = state.getBank().candidates(state.askedIds(), state.getSkillProgress(),
                    sources.isEmpty() ? null : sources, prefer, avoid, 4);
            if (candidates.isEmpty() && !sources.isEmpty()) {
                // 本阶段的来源挑空了就放开来源，总比无题可问好
                candidates = state.getBank().candidates(state.askedIds(), state.getSkillProgress(),
                        null, prefer, avoid, 4);
            }
        }

        List<BankQuestion> pendingMust = state.getBank().pendingMustAsk(state.askedIds());
        if (!pendingMust.isEmpty() && timePressure(state) && !forcePersonality) {
            // 时间不够了，必问项插队
            candidates = pendingMust.size() <= 3 ? pendingMust : pendingMust.subList(0, 3);
        }

        if (candidates.isEmpty()) {
            intents.remove(TurnIntent.ASK_NEW);
        }
        if (state.isPhaseOver() && !intents.contains(TurnIntent.TRANSITION)) {
            intents.add(TurnIntent.TRANSITION);
        }
        if (intents.isEmpty()) {
            intents = new ArrayList<>(List.of(TurnIntent.TRANSITION));
        }

        return new TurnPlan(List.copyOf(intents), candidates, depthAction,
                phaseHint(state, depthAction, pendingMust),
                interruptAllowed, followUpAllowed, forcePersonality, false, prefer, avoid);
    }

    /** 最近一次有评分的回答。没有评分记录时按中位处理。 */
    private static double lastQuality(InterviewState state) {
        List<QuestionRecord> questions = state.getQuestions();
        for (int i = questions.size() - 1; i >= 0; i--) {
            Double quality = questions.get(i).getQuality();
            if (quality != null) {
                return quality;
            }
        }
        return 0.5;
    }

    private static boolean timePressure(InterviewState state) {
        return state.remainingMs() <= state.getPlan().totalMs() * TIME_PRESSURE_RATIO;
    }

    private static String phaseHint(InterviewState state, DepthAction action,
                                    List<BankQuestion> pendingMust) {
        List<String> parts = new ArrayList<>();
        if (action != null) {
            parts.add("上一题的推进判定：" + action.label());
        }
        if (action == DepthAction.ABANDON) {
            parts.add("他这块确实不会，不要再追，换个领域，这一项按低分记录。");
        } else if (action == DepthAction.DEEPEN) {
            QuestionRecord current = state.currentQuestion();
            int depth = Math.min(Depth.MAX, (current == null ? 1 : current.getDepth()) + 1);
            parts.add("可以往 D" + depth + " 深一层。");
        } else if (action == DepthAction.SWITCH) {
            parts.add("这个领域问够了，换一个领域。");
        }

        if (state.isPhaseOver()) {
            parts.add("本阶段时间已用满，尽快切到「" + state.nextPhase().label() + "」。");
        } else {
            long left = Math.max(0, state.phaseBudgetMs() - state.phaseElapsedMs()) / 1000;
            parts.add("本阶段还剩 " + left + " 秒。");
        }
        if (!pendingMust.isEmpty()) {
            List<String> skills = new ArrayList<>();
            pendingMust.stream().limit(4).forEach(q -> skills.add(q.skill()));
            parts.add("仍有 JD 必问项未覆盖：" + String.join("、", skills) + "。");
        }
        if (state.personalityProbeDue()) {
            parts.add("现在是穿插一个性格或价值观问题的合适时机。");
        }
        return String.join(" ", parts);
    }

    /**
     * 规则是最终裁决者。导演还想在一个答不上来的点上纠缠时，这里把它拉走。
     *
     * <p>这是「不会在你确实不会的点上一直折磨你」这条行为的最后一道闸门——前面的候选集
     * 过滤只管新题，追问类意图绕得过去。
     */
    public static DirectorDecision enforceDepthAction(InterviewState state,
                                                      DirectorDecision decision,
                                                      DepthAction action) {
        if (action == null || !action.leavesTopic() || !decision.intent().isProbe()) {
            return decision;
        }

        List<String> avoid = Text.notBlank(state.getCurrentDomain())
                ? List.of(state.getCurrentDomain()) : List.of();
        List<BankQuestion> candidates = state.getBank().candidates(
                state.askedIds(), state.getSkillProgress(), null, null, avoid, 1);

        if (candidates.isEmpty()) {
            log.info("深度规则改判：无可用新题，转为切换环节");
            return decision.asTransition("结束当前话题，切到" + state.nextPhase().label());
        }
        BankQuestion question = candidates.get(0);
        log.info("深度规则改判：{} -> ask_new({})，原因={}",
                decision.intent().value(), question.skill(), action.value());
        return decision.asNewQuestion(question);
    }

    /** 候选人啰嗦多久该被打断。人设的打断倾向越高，阈值越短。 */
    public static long interruptThresholdMs(InterviewState state, OrchestrationSettings settings) {
        double base = settings.getVerboseSecondsBeforeInterrupt();
        return (long) (state.getPersona().interruptThresholdSeconds(base) * 1000);
    }

    /** 返回应当进入的下一阶段，无需切换则返回 null。 */
    public static InterviewPhase resolvePhaseTransition(InterviewState state) {
        if (state.mustClose()) {
            return state.getPhase() == InterviewPhase.CLOSING ? null : InterviewPhase.CLOSING;
        }
        if (state.isPhaseOver()) {
            InterviewPhase next = state.nextPhase();
            return next == state.getPhase() ? null : next;
        }
        return null;
    }

    private static Map<InterviewPhase, List<TurnIntent>> phaseIntents() {
        Map<InterviewPhase, List<TurnIntent>> map = new EnumMap<>(InterviewPhase.class);
        map.put(InterviewPhase.WARMUP, List.of(TurnIntent.ASK_NEW, TurnIntent.FOLLOW_UP,
                TurnIntent.ACKNOWLEDGE, TurnIntent.TRANSITION));
        map.put(InterviewPhase.RESUME_DEEP_DIVE, List.of(TurnIntent.ASK_NEW, TurnIntent.FOLLOW_UP,
                TurnIntent.BOUNDARY_TEST, TurnIntent.PRESSURE, TurnIntent.ACKNOWLEDGE,
                TurnIntent.TRANSITION));
        map.put(InterviewPhase.TECH_DEPTH, List.of(TurnIntent.ASK_NEW, TurnIntent.FOLLOW_UP,
                TurnIntent.BOUNDARY_TEST, TurnIntent.PRESSURE, TurnIntent.ACKNOWLEDGE,
                TurnIntent.TRANSITION));
        map.put(InterviewPhase.BEHAVIORAL, List.of(TurnIntent.ASK_NEW, TurnIntent.FOLLOW_UP,
                TurnIntent.STAR_PROBE, TurnIntent.PRESSURE, TurnIntent.ACKNOWLEDGE,
                TurnIntent.TRANSITION));
        map.put(InterviewPhase.CODING, List.of(TurnIntent.CODING_HANDOFF, TurnIntent.BOUNDARY_TEST,
                TurnIntent.FOLLOW_UP, TurnIntent.ACKNOWLEDGE, TurnIntent.TRANSITION));
        map.put(InterviewPhase.STRESS, List.of(TurnIntent.PRESSURE, TurnIntent.FOLLOW_UP,
                TurnIntent.BOUNDARY_TEST, TurnIntent.ASK_NEW, TurnIntent.TRANSITION));
        map.put(InterviewPhase.CANDIDATE_QA, List.of(TurnIntent.ACKNOWLEDGE, TurnIntent.ASK_NEW,
                TurnIntent.TRANSITION));
        map.put(InterviewPhase.CLOSING, List.of(TurnIntent.CLOSE));
        map.put(InterviewPhase.FINISHED, List.of(TurnIntent.CLOSE));
        return map;
    }

    private static Map<InterviewPhase, List<QuestionSource>> phaseSources() {
        Map<InterviewPhase, List<QuestionSource>> map = new EnumMap<>(InterviewPhase.class);
        map.put(InterviewPhase.WARMUP, List.of(QuestionSource.RESUME_PROJECT,
                QuestionSource.RESUME_SKILL));
        map.put(InterviewPhase.RESUME_DEEP_DIVE, List.of(QuestionSource.RESUME_PROJECT,
                QuestionSource.JD_REQUIREMENT, QuestionSource.RESUME_SKILL));
        map.put(InterviewPhase.TECH_DEPTH, List.of(QuestionSource.JD_REQUIREMENT,
                QuestionSource.FUNDAMENTAL, QuestionSource.RESUME_SKILL));
        map.put(InterviewPhase.BEHAVIORAL, List.of(QuestionSource.BEHAVIORAL));
        map.put(InterviewPhase.CODING, List.of(QuestionSource.CODING));
        map.put(InterviewPhase.STRESS, List.of(QuestionSource.JD_REQUIREMENT,
                QuestionSource.RESUME_PROJECT, QuestionSource.FUNDAMENTAL));
        map.put(InterviewPhase.CANDIDATE_QA, List.of());
        map.put(InterviewPhase.CLOSING, List.of());
        map.put(InterviewPhase.FINISHED, List.of());
        return map;
    }
}
