package com.interviewer.domain.interview;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.Depth;
import com.interviewer.core.type.DriftKind;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.JobLevel;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.core.type.Speaker;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.bank.DepthAction;
import com.interviewer.domain.bank.QuestionBank;
import com.interviewer.domain.bank.SkillProgress;
import com.interviewer.domain.persona.PersonaContract;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * 权威状态。实时模型的记忆不可信，一切以此为准，且每轮落盘。
 *
 * <p>实时语音模型的音频历史会滚动丢弃，长面试必然被截断。所以「当前考察什么、
 * 问过哪些题、技能点推进到哪一层、还剩多少时间」这些事实只存在这里，每轮重新
 * 注入给模型。人格漂移因此在架构上不可能发生。
 */
@Getter
@Setter
public class InterviewState {

    private int sessionId;
    private PersonaContract persona;
    private InterviewPlan plan;
    private QuestionBank bank = new QuestionBank();
    private CompanyTier companyTier = CompanyTier.MID_TECH;
    private JobLevel jobLevel = JobLevel.MID;
    private String jobTitle = "";
    private String resumeDigest = "";
    private String jdDigest = "";
    private String gapDigest = "";

    private InterviewPhase phase = InterviewPhase.WARMUP;
    private long phaseStartedAtMs = 0;
    private long elapsedMs = 0;

    private int turnIndex = 0;
    private int questionIndex = 0;
    private List<TurnRecord> turns = new ArrayList<>();
    private List<QuestionRecord> questions = new ArrayList<>();
    /** 开场到第一题问出去之前是空的。 */
    @Schema(nullable = true)
    private Integer currentQuestionIndex;

    private int followUpDepth = 0;
    private List<String> pendingSkills = new ArrayList<>();
    private List<String> coveredSkills = new ArrayList<>();

    private List<Integer> askedBankIds = new ArrayList<>();
    private Map<String, SkillProgress> skillProgress = new LinkedHashMap<>();
    private List<String> domainsVisited = new ArrayList<>();
    private String currentDomain = "";
    @Schema(nullable = true)
    private DepthAction lastDepthAction;
    private int questionsSincePersonality = Plans.PERSONALITY_GAP;
    private int personalityProbesUsed = 0;

    private int interruptsUsedInPhase = 0;
    private int driftCount = 0;
    private DriftKind lastDrift = DriftKind.NONE;
    private int turnsSinceReanchor = 0;

    private StarState star = new StarState();
    private Map<ScoreDimension, Double> liveScores = new LinkedHashMap<>();
    private boolean codingActive = false;
    private String codeLanguage = "python";
    private String codeSnapshot = "";

    /**
     * 最近几轮同技能点的评分，仅供导演评分时做前后一致性参照。
     *
     * <p>内存字段，不落盘、不进 JSON：进程重启即清空，老数据库无需迁移。
     */
    @JsonIgnore
    private final List<QualityMark> recentQualityLog = new ArrayList<>();

    /** 一次评分留痕。 */
    public record QualityMark(String skill, double score, int turn) {
    }

    // ---------- 查询 ----------

    @JsonIgnore
    public QuestionRecord currentQuestion() {
        if (currentQuestionIndex == null) {
            return null;
        }
        return questions.stream()
                .filter(q -> q.getIndex() == currentQuestionIndex)
                .findFirst().orElse(null);
    }

    @JsonIgnore
    public long phaseElapsedMs() {
        return Math.max(0, elapsedMs - phaseStartedAtMs);
    }

    @JsonIgnore
    public long remainingMs() {
        return Math.max(0, plan.totalMs() - elapsedMs);
    }

    /**
     * 够不够时长出一份完整复盘。
     *
     * <p>必须跟着状态过网络边界：复盘页要靠它决定是出报告还是出「时长太短」。
     * 只留内部方法的话前端读成 undefined，每场都会判过短。
     */
    @JsonProperty("reviewable")
    public boolean isReviewable() {
        return elapsedMs >= Plans.MIN_REVIEWABLE_MS;
    }

    @JsonIgnore
    public long phaseBudgetMs() {
        PhaseSlot slot = plan.slotOf(phase);
        return slot == null ? 0 : slot.budgetMs();
    }

    @JsonIgnore
    public List<QuestionRecord> questionsInPhase() {
        return questions.stream().filter(q -> q.getPhase() == phase).toList();
    }

    @JsonIgnore
    public TurnRecord lastCandidateTurn() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).getSpeaker() == Speaker.CANDIDATE) {
                return turns.get(i);
            }
        }
        return null;
    }

    @JsonIgnore
    public List<TurnRecord> recentDialogue(int limit) {
        int from = Math.max(0, turns.size() - limit);
        return List.copyOf(turns.subList(from, turns.size()));
    }

    @JsonIgnore
    public Set<Integer> askedIds() {
        return new LinkedHashSet<>(askedBankIds);
    }

    /** 打断倾向 ≤1 的人设兑现「绝不打断」的承诺，额度再多也不用。 */
    @JsonIgnore
    public boolean canInterrupt(int budgetPerPhase) {
        if (persona.getPressure().getInterruptTendency() <= 1) {
            return false;
        }
        return interruptsUsedInPhase < budgetPerPhase;
    }

    /** 追问上限同时受配置与人设约束，取更小的那个。 */
    @JsonIgnore
    public boolean canFollowUp(int maxDepth) {
        int personaCeiling = Math.max(1, persona.getProbing().getFollowUpDepth() / 2 + 1);
        return followUpDepth < Math.min(maxDepth, personaCeiling);
    }

    /** 硬闸门。到点就收尾，不给模型任何自由裁量。 */
    @JsonIgnore
    public boolean mustClose() {
        if (phase == InterviewPhase.CLOSING || phase == InterviewPhase.FINISHED) {
            return true;
        }
        return remainingMs() <= plan.closingMs();
    }

    @JsonIgnore
    public boolean isPhaseOver() {
        PhaseSlot slot = plan.slotOf(phase);
        if (slot == null) {
            return true;
        }
        boolean askedEnough = questionsInPhase().size() >= slot.minQuestions();
        return phaseElapsedMs() >= slot.budgetMs() && askedEnough;
    }

    @JsonIgnore
    public InterviewPhase nextPhase() {
        List<InterviewPhase> phases = plan.phases();
        int idx = phases.indexOf(phase);
        if (idx < 0) {
            return InterviewPhase.CLOSING;
        }
        return idx + 1 < phases.size() ? phases.get(idx + 1) : InterviewPhase.FINISHED;
    }

    /** 只在技术题问够间隔、且不在编码或收尾时才允许穿插性格题。 */
    @JsonIgnore
    public boolean personalityProbeDue() {
        if (phase == InterviewPhase.CODING || phase == InterviewPhase.CLOSING
                || phase == InterviewPhase.FINISHED || phase == InterviewPhase.BEHAVIORAL) {
            return false;
        }
        if (persona.getProbing().getBehavioralFocus() < 3) {
            return false;
        }
        return questionsSincePersonality >= Plans.PERSONALITY_GAP;
    }

    @JsonIgnore
    public SkillProgress progressOf(String skill) {
        return skillProgress.get(SkillProgress.key(skill));
    }

    @JsonIgnore
    public List<SkillProgress> exhaustedSkills() {
        return skillProgress.values().stream().filter(SkillProgress::isExhausted).toList();
    }

    // ---------- 变更 ----------

    public void enterPhase(InterviewPhase target) {
        this.phase = target;
        this.phaseStartedAtMs = elapsedMs;
        this.interruptsUsedInPhase = 0;
        this.followUpDepth = 0;
        this.star.reset(target == InterviewPhase.BEHAVIORAL);
        this.codingActive = target == InterviewPhase.CODING;
    }

    public QuestionRecord openQuestion(TurnIntent intent, String brief, String targetSkill,
                                       String domain, int depth, Integer bankQuestionId,
                                       boolean isPersonality) {
        questionIndex++;
        if (intent.isProbe()) {
            followUpDepth++;
        } else {
            followUpDepth = 0;
        }

        QuestionRecord record = new QuestionRecord();
        record.setIndex(questionIndex);
        record.setPhase(phase);
        record.setIntent(intent);
        record.setBrief(brief);
        record.setTargetSkill(targetSkill);
        record.setDomain(Text.notBlank(domain) ? domain : currentDomain);
        record.setDepth(Depth.clamp(depth));
        record.setBankQuestionId(bankQuestionId);
        record.setAskedAtMs(elapsedMs);
        record.setFollowUpDepth(followUpDepth);
        questions.add(record);
        currentQuestionIndex = record.getIndex();

        if (bankQuestionId != null && !askedBankIds.contains(bankQuestionId)) {
            askedBankIds.add(bankQuestionId);
        }
        if (Text.notBlank(targetSkill)) {
            SkillProgress.forSkill(skillProgress, targetSkill, record.getDomain());
            markSkillTouched(targetSkill);
        }
        if (!record.getDomain().isEmpty()) {
            currentDomain = record.getDomain();
            if (!domainsVisited.contains(record.getDomain())) {
                domainsVisited.add(record.getDomain());
            }
        }

        if (isPersonality) {
            questionsSincePersonality = 0;
            personalityProbesUsed++;
        } else if (intent.opensNewQuestion()) {
            questionsSincePersonality++;
        }
        return record;
    }

    /** 把回答质量落到技能推进上，返回确定性的下一步动作。 */
    public DepthAction observeAnswer(Double quality) {
        QuestionRecord question = currentQuestion();
        if (question == null || question.getTargetSkill().isEmpty()) {
            return null;
        }
        question.setQuality(quality);
        SkillProgress state = SkillProgress.forSkill(
                skillProgress, question.getTargetSkill(), question.getDomain());
        DepthAction action = state.observe(quality);
        question.setDepthAction(action);
        lastDepthAction = action;
        if (quality != null) {
            recentQualityLog.add(new QualityMark(question.getTargetSkill(), quality, turnIndex));
            // 限长避免越聊越长，超过 5 条就裁掉最旧
            while (recentQualityLog.size() > 5) {
                recentQualityLog.remove(0);
            }
        }
        return action;
    }

    /** 与该技能点相关的最近评分，供导演对照。严格匹配优先，没有则放宽到全部。 */
    @JsonIgnore
    public List<QualityMark> recentSkillScores(String skill, int limit) {
        String key = SkillProgress.key(skill);
        List<QualityMark> matched = recentQualityLog.stream()
                .filter(m -> SkillProgress.key(m.skill()).equals(key))
                .toList();
        List<QualityMark> source = matched.isEmpty() ? recentQualityLog : matched;
        int from = Math.max(0, source.size() - limit);
        return List.copyOf(source.subList(from, source.size()));
    }

    public TurnRecord appendTurn(Speaker speaker, String text, long startedAtMs, long durationMs,
                                 TurnIntent intent, boolean wasInterrupted) {
        turnIndex++;
        TurnRecord turn = new TurnRecord(turnIndex, speaker, text, startedAtMs, durationMs,
                intent, wasInterrupted, currentQuestionIndex);
        turns.add(turn);
        turnsSinceReanchor++;
        QuestionRecord question = currentQuestion();
        if (question != null) {
            if (speaker == Speaker.INTERVIEWER && question.getSpokenText().isEmpty()) {
                question.setSpokenText(text);
            } else if (speaker == Speaker.CANDIDATE) {
                question.appendAnswer(text);
            }
        }
        return turn;
    }

    public void markSkillTouched(String skill) {
        if (!Text.notBlank(skill)) {
            return;
        }
        String normalized = skill.strip();
        String key = SkillProgress.key(normalized);
        pendingSkills.removeIf(s -> SkillProgress.key(s).equals(key));
        boolean known = coveredSkills.stream().anyMatch(s -> SkillProgress.key(s).equals(key));
        if (!known) {
            coveredSkills.add(normalized);
        }
    }

    public void registerDrift(DriftKind kind) {
        driftCount++;
        lastDrift = kind;
    }

    public void noteReanchor() {
        turnsSinceReanchor = 0;
        lastDrift = DriftKind.NONE;
    }

    @JsonIgnore
    public boolean needsReanchor(int everyTurns) {
        return turnsSinceReanchor >= everyTurns || lastDrift != DriftKind.NONE;
    }

    /** 实时分做指数平滑：单轮的判断噪声大，不能直接顶掉累积值。 */
    public void blendScores(Map<ScoreDimension, Double> partial, double weight) {
        partial.forEach((dim, value) -> {
            Double current = liveScores.get(dim);
            liveScores.put(dim, current == null ? value : current * (1 - weight) + value * weight);
        });
    }

    public void blendScores(Map<ScoreDimension, Double> partial) {
        blendScores(partial, 0.35);
    }

    // ---------- 注入模型的状态摘要 ----------

    /**
     * 权威进度摘要。与模型记忆冲突时一律以本节为准。
     *
     * <p>每一段都在解决一个具体的漂移：已问过的题防重复、技能点推进状态防乱跳深度、
     * 已放弃清单防死缠、必考清单防漏考、打断额度防失控。
     */
    @JsonIgnore
    public String digest() {
        List<String> lines = new ArrayList<>();
        lines.add("【当前进度】");
        lines.add("- 第 " + turnIndex + " 轮对话｜阶段：" + phase.label()
                + "（本阶段已用 " + Text.mmss(phaseElapsedMs())
                + " / 预算 " + Text.mmss(phaseBudgetMs()) + "）");
        lines.add("- 整场已用 " + Text.mmss(elapsedMs) + "，剩余约 " + Text.mmss(remainingMs())
                + "（总时长 " + plan.totalMs() / 60000 + " 分钟，到点必须收尾）");

        List<QuestionRecord> asked = tail(questions, 8);
        if (!asked.isEmpty()) {
            lines.add("【你已经问过的问题｜严禁重复】");
            asked.forEach(q -> lines.add(q.getIndex() + ". [" + q.getPhase().label()
                    + " D" + q.getDepth() + "] " + q.shortText()));
        }

        List<SkillProgress> active = skillProgress.values().stream()
                .filter(p -> p.getAttempts() > 0 && !p.isExhausted())
                .toList();
        if (!active.isEmpty()) {
            lines.add("【技能点推进状态】");
            tail(active, 6).forEach(p -> lines.add("- " + p.summary()));
        }

        List<SkillProgress> dropped = exhaustedSkills();
        if (!dropped.isEmpty()) {
            String names = dropped.stream().limit(6)
                    .map(p -> p.getSkill() + "(D" + p.getAbandonedAtDepth() + ")")
                    .reduce((a, b) -> a + "、" + b).orElse("");
            lines.add("【已放弃深挖｜不要再问这些，他确实不会】" + names);
        }

        if (!pendingSkills.isEmpty()) {
            lines.add("【必须考到但还没考的技能点】"
                    + String.join("、", head(pendingSkills, 8)));
        }
        if (!domainsVisited.isEmpty()) {
            lines.add("【已覆盖领域】" + String.join("、", tail(domainsVisited, 8)));
        }

        String starDesc = star.describe();
        if (!starDesc.isEmpty()) {
            lines.add("【当前行为题 STAR 完整度】" + starDesc);
        }

        if (codingActive) {
            lines.add("【编码环节进行中】围绕他屏幕上的代码提问，不要给出实现。");
        }

        lines.add("【打断额度】本阶段已打断 " + interruptsUsedInPhase + " 次"
                + "｜追问深度 " + followUpDepth
                + "｜性格题已穿插 " + personalityProbesUsed + " 次");
        if (mustClose()) {
            lines.add("【硬性要求】时间已到收尾线，本轮必须结束面试，不得再提新问题。");
        }
        return String.join("\n", lines);
    }

    /** 面试前已掌握的资料。它是静态的，只在重锚时随人格锚点一起重发。 */
    @JsonIgnore
    public String contextBlock() {
        List<String> parts = new ArrayList<>();
        List<String> header = new ArrayList<>();
        if (!jobTitle.isEmpty()) {
            header.add("目标岗位：" + jobTitle);
        }
        header.add("公司类型：" + companyTier.label());
        header.add("目标级别：" + jobLevel.label());
        parts.add("【本场设定】" + String.join("｜", header));
        if (!resumeDigest.isEmpty()) {
            parts.add("【候选人简历要点】\n" + resumeDigest);
        }
        if (!jdDigest.isEmpty()) {
            parts.add("【目标岗位要求】\n" + jdDigest);
        }
        if (!gapDigest.isEmpty()) {
            parts.add("【面试前诊断结论｜你要重点验证这些疑点】\n" + gapDigest);
        }
        return String.join("\n\n", parts);
    }

    private static <T> List<T> tail(List<T> list, int limit) {
        int from = Math.max(0, list.size() - limit);
        return Collections.unmodifiableList(list.subList(from, list.size()));
    }

    private static <T> List<T> head(List<T> list, int limit) {
        return list.size() <= limit ? list : list.subList(0, limit);
    }
}
