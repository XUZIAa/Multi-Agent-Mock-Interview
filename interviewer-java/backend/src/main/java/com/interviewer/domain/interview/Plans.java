package com.interviewer.domain.interview;

import com.interviewer.core.type.InterviewPhase;
import com.interviewer.domain.company.CompanyProfile;
import com.interviewer.domain.company.Companies;
import com.interviewer.domain.persona.PersonaContract;
import com.interviewer.domain.persona.PressureProfile;
import com.interviewer.domain.persona.ProbingProfile;
import com.interviewer.domain.resume.GapReport;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 面试排期。人设的考察偏好、公司画像、诊断结论三者一起决定每个环节分到多少时间。 */
public final class Plans {

    /** 时长档位。用户只能选档，不能在面试中自行延长。 */
    public static final List<Integer> DURATION_CHOICES = List.of(10, 20, 30, 45);

    /** 低于这个时长不足以支撑完整复盘。 */
    public static final long MIN_REVIEWABLE_MS = 5 * 60 * 1000L;

    /** 每问几个技术题后允许穿插一次性格题。 */
    public static final int PERSONALITY_GAP = 4;

    /** 固定占比的环节。开场、候选人提问、收尾的时长不该随人设浮动。 */
    private static final Map<InterviewPhase, Double> FIXED_SHARE = Map.of(
            InterviewPhase.WARMUP, 0.09,
            InterviewPhase.CANDIDATE_QA, 0.08,
            InterviewPhase.CLOSING, 0.06);

    /** 权重低于这个值的动态环节直接不排，省下时间给真正要考的。 */
    private static final double MIN_DYNAMIC_WEIGHT = 0.4;

    private Plans() {
    }

    /** 短面试必须砍环节，否则每个环节都走不完。 */
    static Set<InterviewPhase> phaseSet(int minutes, boolean codingEnabled) {
        Set<InterviewPhase> phases = EnumSet.of(
                InterviewPhase.WARMUP, InterviewPhase.RESUME_DEEP_DIVE,
                InterviewPhase.TECH_DEPTH, InterviewPhase.CLOSING);
        if (minutes >= 20) {
            phases.add(InterviewPhase.BEHAVIORAL);
        }
        if (minutes >= 25) {
            phases.add(InterviewPhase.CANDIDATE_QA);
        }
        if (minutes >= 35) {
            phases.add(InterviewPhase.STRESS);
        }
        if (codingEnabled && minutes >= 20) {
            phases.add(InterviewPhase.CODING);
        }
        return phases;
    }

    public static InterviewPlan build(PersonaContract persona, int plannedMinutes,
                                      boolean codingEnabled, GapReport gap) {
        long totalMs = plannedMinutes * 60_000L;
        Set<InterviewPhase> allowed = phaseSet(plannedMinutes, codingEnabled);
        ProbingProfile probing = persona.getProbing();
        PressureProfile pressure = persona.getPressure();
        CompanyProfile company = Companies.of(persona.getCompanyTier());

        Map<InterviewPhase, Double> dynamic = new EnumMap<>(InterviewPhase.class);
        dynamic.put(InterviewPhase.RESUME_DEEP_DIVE,
                probing.getProjectFocus() + company.project() * 0.5);
        dynamic.put(InterviewPhase.TECH_DEPTH,
                (probing.getFundamentalsFocus() + probing.getSystemDesignFocus()) / 2.0
                        + (company.fundamentals() + company.systemDesign()) * 0.25);
        dynamic.put(InterviewPhase.BEHAVIORAL,
                probing.getBehavioralFocus() + company.process() * 0.3);
        dynamic.put(InterviewPhase.STRESS,
                (pressure.getAggression() + pressure.getChallengeFrequency()) / 4.0);
        dynamic.put(InterviewPhase.CODING,
                Math.max(probing.getCodingFocus(), 4) + company.algorithm() * 0.3);

        if (gap != null) {
            gap.getPhaseEmphasis().forEach((phase, weight) ->
                    dynamic.computeIfPresent(phase, (p, w) -> w + weight));
        }

        dynamic.keySet().removeIf(p -> !allowed.contains(p)
                || dynamic.get(p) <= MIN_DYNAMIC_WEIGHT);
        if (dynamic.isEmpty()) {
            dynamic.put(InterviewPhase.TECH_DEPTH, 1.0);
        }

        Map<InterviewPhase, Long> fixedMs = new EnumMap<>(InterviewPhase.class);
        FIXED_SHARE.forEach((phase, share) -> {
            if (allowed.contains(phase)) {
                fixedMs.put(phase, (long) (totalMs * share));
            }
        });
        fixedMs.put(InterviewPhase.CLOSING, Math.max(InterviewPlan.MIN_CLOSING_MS,
                fixedMs.getOrDefault(InterviewPhase.CLOSING, InterviewPlan.MIN_CLOSING_MS)));

        long fixedTotal = fixedMs.values().stream().mapToLong(Long::longValue).sum();
        long remaining = Math.max(60_000L, totalMs - fixedTotal);
        double weightSum = dynamic.values().stream().mapToDouble(Double::doubleValue).sum();

        List<PhaseSlot> slots = new ArrayList<>();
        for (InterviewPhase phase : InterviewPhase.ORDER) {
            if (!allowed.contains(phase)) {
                continue;
            }
            if (fixedMs.containsKey(phase)) {
                slots.add(new PhaseSlot(phase, fixedMs.get(phase), 1));
            } else if (dynamic.containsKey(phase)) {
                long budget = (long) (remaining * dynamic.get(phase) / weightSum);
                // 每 200 秒预算加一道最少题数：时间给够了却只问一题，等于白排
                slots.add(new PhaseSlot(phase, budget, (int) Math.max(1, budget / 200_000 + 1)));
            }
        }
        return new InterviewPlan(totalMs, slots);
    }
}
