package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.error.ProviderResponseException;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.AnnotationKind;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.GapSeverity;
import com.interviewer.core.type.Labeled;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.domain.company.Companies;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.review.AbandonedSkill;
import com.interviewer.domain.review.AnswerRewrite;
import com.interviewer.domain.review.DimensionScore;
import com.interviewer.domain.review.DrillItem;
import com.interviewer.domain.review.ImprovementPlan;
import com.interviewer.domain.review.MistakeItem;
import com.interviewer.domain.review.ProsodyReport;
import com.interviewer.domain.review.ReviewReport;
import com.interviewer.domain.review.TranscriptAnnotation;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 面试后的离线复盘。四个子任务并发跑。
 *
 * <p>评分是骨架，缺了整份报告就没有意义，所以它失败就整体失败并说清是哪一步。
 * 批注、重构、错题是血肉，缺一块报告仍然有用，但必须在报告里显式标出来——
 * 静默给个空数组会让用户以为「模型觉得没什么可说的」。
 */
@Component
public class Reviewer extends Agent {

    private static final Logger log = LoggerFactory.getLogger(Reviewer.class);

    /** 四个子任务的总时限。analyst 角色本身的读超时是 4 分钟，这里留足余量。 */
    private static final Duration FANOUT_TIMEOUT = Duration.ofMinutes(5);

    /** 维度的展示顺序，同时是加权的基准顺序。 */
    private static final List<ScoreDimension> DIMENSION_ORDER = List.of(
            ScoreDimension.TECH_DEPTH, ScoreDimension.EXPRESSION, ScoreDimension.RESILIENCE,
            ScoreDimension.VALUE_FIT, ScoreDimension.CODING, ScoreDimension.COLLABORATION);

    private final AgentTasks tasks;

    public Reviewer(LlmRouter router, AgentTasks tasks) {
        super(router);
        this.tasks = tasks;
    }

    @Override
    protected String role() {
        return Providers.ROLE_ANALYST;
    }

    /** 进度回调。出题与复盘都要几十秒，进度塞不进 HTTP 响应，只能另路回推。 */
    public interface ProgressHook {
        void report(String stage, int percent, String detail);
    }

    /** 复盘的输入材料。语音指标由程序测得，不让模型改动那些数字。 */
    public record Material(String transcript, String questionDigest, String codingSummary,
                           ProsodyReport prosody, String prosodySummary) {
    }

    // ---------- 模型原始输出 ----------

    record DimRaw(@JsonProperty("dimension") @JsonAlias({"name"}) String dimension,
                  @JsonProperty("score") double score,
                  @JsonProperty("reason") String reason,
                  @JsonProperty("evidence") List<String> evidence) {

        DimRaw(String dimension, double score, String reason, List<String> evidence) {
            this.dimension = Text.safe(dimension);
            this.score = score;
            this.reason = Text.safe(reason);
            this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record ScoreRaw(@JsonProperty("overall_score") double overallScore,
                    @JsonProperty("headline") String headline,
                    @JsonProperty("summary") String summary,
                    @JsonProperty("dimensions") List<DimRaw> dimensions,
                    @JsonProperty("strengths") List<String> strengths,
                    @JsonProperty("improvements") List<String> improvements,
                    @JsonProperty("next_actions") List<String> nextActions) {

        ScoreRaw(double overallScore, String headline, String summary, List<DimRaw> dimensions,
                 List<String> strengths, List<String> improvements, List<String> nextActions) {
            this.overallScore = overallScore;
            this.headline = Text.safe(headline);
            this.summary = Text.safe(summary);
            this.dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            this.strengths = strengths == null ? List.of() : List.copyOf(strengths);
            this.improvements = improvements == null ? List.of() : List.copyOf(improvements);
            this.nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        }
    }

    record AnnotationRaw(@JsonProperty("turn_index") int turnIndex,
                         @JsonProperty("kind") String kind,
                         @JsonProperty("quote") @JsonAlias({"name", "text"}) String quote,
                         @JsonProperty("comment") String comment) {

        AnnotationRaw(int turnIndex, String kind, String quote, String comment) {
            this.turnIndex = turnIndex;
            this.kind = Text.safe(kind).isEmpty() ? "weakness" : Text.safe(kind);
            this.quote = Text.safe(quote);
            this.comment = Text.safe(comment);
        }
    }

    record AnnotationsRaw(@JsonProperty("annotations") List<AnnotationRaw> annotations) {

        AnnotationsRaw(List<AnnotationRaw> annotations) {
            this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }
    }

    record RewriteRaw(@JsonProperty("question_index") int questionIndex,
                      @JsonProperty("question") @JsonAlias({"name"}) String question,
                      @JsonProperty("original") String original,
                      @JsonProperty("rewritten") String rewritten,
                      @JsonProperty("why_better") List<String> whyBetter,
                      @JsonProperty("used_assets") List<String> usedAssets) {

        RewriteRaw(int questionIndex, String question, String original, String rewritten,
                   List<String> whyBetter, List<String> usedAssets) {
            this.questionIndex = questionIndex;
            this.question = Text.safe(question);
            this.original = Text.safe(original);
            this.rewritten = Text.safe(rewritten);
            this.whyBetter = whyBetter == null ? List.of() : List.copyOf(whyBetter);
            this.usedAssets = usedAssets == null ? List.of() : List.copyOf(usedAssets);
        }
    }

    record RewritesRaw(@JsonProperty("rewrites") List<RewriteRaw> rewrites) {

        RewritesRaw(List<RewriteRaw> rewrites) {
            this.rewrites = rewrites == null ? List.of() : List.copyOf(rewrites);
        }
    }

    record MistakeRaw(@JsonProperty("knowledge_point") @JsonAlias({"name", "point"})
                      String knowledgePoint,
                      @JsonProperty("topic") String topic,
                      @JsonProperty("question") String question,
                      @JsonProperty("candidate_answer") String candidateAnswer,
                      @JsonProperty("key_points") List<String> keyPoints,
                      @JsonProperty("severity") String severity,
                      @JsonProperty("review_hint") String reviewHint) {

        MistakeRaw(String knowledgePoint, String topic, String question, String candidateAnswer,
                   List<String> keyPoints, String severity, String reviewHint) {
            this.knowledgePoint = Text.safe(knowledgePoint);
            this.topic = Text.safe(topic);
            this.question = Text.safe(question);
            this.candidateAnswer = Text.safe(candidateAnswer);
            this.keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
            this.severity = Text.safe(severity).isEmpty() ? "major" : Text.safe(severity);
            this.reviewHint = Text.safe(reviewHint);
        }
    }

    record MistakesRaw(@JsonProperty("mistakes") List<MistakeRaw> mistakes) {

        MistakesRaw(List<MistakeRaw> mistakes) {
            this.mistakes = mistakes == null ? List.of() : List.copyOf(mistakes);
        }
    }

    record DrillRaw(@JsonProperty("action") @JsonAlias({"name", "task"}) String action,
                    @JsonProperty("why") String why,
                    @JsonProperty("time_cost") String timeCost) {

        DrillRaw(String action, String why, String timeCost) {
            this.action = Text.safe(action);
            this.why = Text.safe(why);
            this.timeCost = Text.safe(timeCost);
        }
    }

    record PlanRaw(@JsonProperty("focus_area") @JsonAlias({"name", "area", "focus"})
                   String focusArea,
                   @JsonProperty("diagnosis") String diagnosis,
                   @JsonProperty("expected_gain") String expectedGain,
                   @JsonProperty("drills") List<DrillRaw> drills,
                   @JsonProperty("resources") List<String> resources,
                   @JsonProperty("next_mock_setup") String nextMockSetup) {

        PlanRaw(String focusArea, String diagnosis, String expectedGain, List<DrillRaw> drills,
                List<String> resources, String nextMockSetup) {
            this.focusArea = Text.safe(focusArea);
            this.diagnosis = Text.safe(diagnosis);
            this.expectedGain = Text.safe(expectedGain);
            this.drills = drills == null ? List.of() : List.copyOf(drills);
            this.resources = resources == null ? List.of() : List.copyOf(resources);
            this.nextMockSetup = Text.safe(nextMockSetup);
        }
    }

    record PlansRaw(@JsonProperty("plans") List<PlanRaw> plans) {

        PlansRaw(List<PlanRaw> plans) {
            this.plans = plans == null ? List.of() : List.copyOf(plans);
        }
    }

    // ---------- 主流程 ----------

    public ReviewReport compose(InterviewState state, Material material, ProgressHook hook) {
        String baseContext = Prompts.reviewUser(
                state.getPersona().getName() + "（" + state.getPersona().displayArchetype() + "）",
                state.getJdDigest(), state.getResumeDigest(),
                material.transcript(), material.codingSummary(), material.prosodySummary());
        boolean hasCoding = Text.notBlank(material.codingSummary());

        report(hook, "scoring", 10, "正在给多维能力打分");

        List<Supplier<Object>> jobs = List.of(
                () -> score(baseContext, hasCoding),
                () -> annotate(baseContext),
                () -> rewrite(baseContext, material.questionDigest()),
                () -> mistakes(baseContext, material.questionDigest()));
        List<AgentTasks.Settled<Object>> results = tasks.allSettled(jobs, FANOUT_TIMEOUT);

        report(hook, "assembling", 85, "正在汇总报告");

        // 评分是骨架，它失败就没有报告可谈——把原因说清而不是给一份空壳
        AgentTasks.Settled<Object> scored = results.get(0);
        if (!scored.ok()) {
            throw new ProviderResponseException(
                    "复盘评分子任务失败: " + rootMessage(scored.error()),
                    "复盘生成失败：能力评分这一步没能完成。"
                            + "建议检查「设置 → 模型」里复盘模型的配置，或稍后重试。");
        }

        ScoreRaw scoreRaw = (ScoreRaw) scored.value();
        AnnotationsRaw annotationsRaw = unwrap(results.get(1), new AnnotationsRaw(null), "逐字稿批注");
        RewritesRaw rewritesRaw = unwrap(results.get(2), new RewritesRaw(null), "满分答案重构");
        MistakesRaw mistakesRaw = unwrap(results.get(3), new MistakesRaw(null), "错题提取");

        List<String> degraded = new ArrayList<>();
        if (!results.get(1).ok()) {
            degraded.add("逐字稿批注");
        }
        if (!results.get(2).ok()) {
            degraded.add("满分答案重构");
        }
        if (!results.get(3).ok()) {
            degraded.add("错题提取");
        }

        List<DimensionScore> dimensions = buildDimensions(scoreRaw.dimensions(), hasCoding);
        // 总分以确定性加权为准，模型的整体判断只在维度缺失时兜底
        double overall = dimensions.isEmpty()
                ? Text.clamp(scoreRaw.overallScore(), 0.0, 100.0)
                : weightedOverall(dimensions, state.getCompanyTier());

        List<AbandonedSkill> abandoned = new ArrayList<>();
        state.exhaustedSkills().forEach(p -> abandoned.add(new AbandonedSkill(
                p.getSkill(), p.getDomain(), p.getAbandonedAtDepth(), p.getAttempts())));

        ReviewReport report = new ReviewReport();
        report.setSessionId(state.getSessionId());
        report.setDurationMs(state.getElapsedMs());
        report.setReviewable(state.isReviewable());
        report.setOverallScore(overall);
        report.setHeadline(Text.trim(scoreRaw.headline(), 120));
        report.setSummary(Text.cut(scoreRaw.summary().strip(), 1200));
        report.setDimensions(dimensions);
        report.setAnnotations(buildAnnotations(annotationsRaw.annotations(), state.getTurns().size()));
        report.setRewrites(buildRewrites(rewritesRaw.rewrites()));
        report.setMistakes(buildMistakes(mistakesRaw.mistakes()));
        report.setProsody(material.prosody());
        report.setStrengths(cap(scoreRaw.strengths(), 5, 60));
        report.setImprovements(cap(scoreRaw.improvements(), 5, 60));
        report.setNextActions(cap(scoreRaw.nextActions(), 5, 80));
        report.setAbandonedSkills(abandoned);
        report.setDegradedSections(List.copyOf(degraded));

        if (!degraded.isEmpty()) {
            log.warn("复盘部分子任务失败: {}", String.join("、", degraded));
        }

        report(hook, "improvement", 92, "正在生成专项提升方案");
        report.setImprovementPlans(improvement(report, abandoned, state.getJdDigest()));
        report(hook, "done", 100, "复盘完成");
        return report;
    }

    // ---------- 子任务 ----------

    private ScoreRaw score(String context, boolean hasCoding) {
        String note = hasCoding ? ""
                : "\n\n注意：本场没有编码环节，结果里不要出现 coding 维度。";
        return client().structured(messages(Prompts.REVIEW_SCORE_SYSTEM, context + note),
                ScoreRaw.class, 0.3, 4000, 1);
    }

    private AnnotationsRaw annotate(String context) {
        return client().structured(messages(Prompts.ANNOTATE_SYSTEM, context),
                AnnotationsRaw.class, 0.3, 4000, 1);
    }

    private RewritesRaw rewrite(String context, String questionDigest) {
        return client().structured(
                messages(Prompts.REWRITE_SYSTEM,
                        context + "\n\n【按题号整理的问答】\n" + questionDigest),
                RewritesRaw.class, 0.5, 5000, 1);
    }

    private MistakesRaw mistakes(String context, String questionDigest) {
        return client().structured(
                messages(Prompts.MISTAKES_SYSTEM,
                        context + "\n\n【按题号整理的问答】\n" + questionDigest),
                MistakesRaw.class, 0.2, 3500, 1);
    }

    private List<ImprovementPlan> improvement(ReviewReport report, List<AbandonedSkill> abandoned,
                                              String jdDigest) {
        StringBuilder dims = new StringBuilder();
        for (DimensionScore d : report.getDimensions()) {
            dims.append("- ").append(d.dimension().label()).append("：")
                    .append(Text.fixed(d.score(), 0)).append(" 分。").append(d.reason()).append('\n');
        }
        StringBuilder drops = new StringBuilder();
        for (AbandonedSkill a : abandoned) {
            drops.append("- ").append(a.skill()).append("（").append(a.domain()).append("）：问到 D")
                    .append(a.abandonedAtDepth()).append(" 就答不上来，共尝试 ")
                    .append(a.attempts()).append(" 次\n");
        }
        StringBuilder wrong = new StringBuilder();
        report.getMistakes().stream().limit(8).forEach(m ->
                wrong.append("- ").append(m.knowledgePoint()).append("（").append(m.topic())
                        .append("）：").append(m.candidateAnswer()).append('\n'));

        String dimLines = dims.length() == 0 ? "（无维度数据）" : dims.toString().stripTrailing();
        PlansRaw raw = tasks.withTimeout(FANOUT_TIMEOUT,
                () -> client().structured(
                        messages(Prompts.IMPROVEMENT_SYSTEM, Prompts.improvementUser(
                                report.getHeadline(), dimLines,
                                drops.toString().stripTrailing(),
                                wrong.toString().stripTrailing(), jdDigest)),
                        PlansRaw.class, 0.4, 3500, 1),
                null, "专项提升方案");
        return raw == null ? List.of() : buildPlans(raw.plans());
    }

    private static List<Message> messages(String system, String user) {
        return List.of(new SystemMessage(system), new UserMessage(user));
    }

    private static void report(ProgressHook hook, String stage, int percent, String detail) {
        if (hook != null) {
            hook.report(stage, percent, detail);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T unwrap(AgentTasks.Settled<Object> result, T fallback, String taskName) {
        if (result.ok()) {
            return (T) result.value();
        }
        log.error("复盘子任务失败 - {}: {}", taskName, rootMessage(result.error()));
        return fallback;
    }

    private static String rootMessage(Throwable e) {
        if (e == null) {
            return "未知原因";
        }
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return Text.cut(msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg, 200);
    }

    // ---------- 组装 ----------

    /** 认不出的维度名丢掉，重复的只留第一个，没有编码环节时剔掉 coding。 */
    private static List<DimensionScore> buildDimensions(List<DimRaw> raw, boolean hasCoding) {
        Set<ScoreDimension> seen = new LinkedHashSet<>();
        List<DimensionScore> out = new ArrayList<>();
        for (DimRaw item : raw) {
            ScoreDimension dim = ScoreDimension.of(item.dimension());
            if (dim == null || seen.contains(dim)) {
                continue;
            }
            if (dim == ScoreDimension.CODING && !hasCoding) {
                continue;
            }
            seen.add(dim);
            out.add(new DimensionScore(dim, Text.clamp(item.score(), 0.0, 100.0),
                    Text.trim(item.reason(), 160), cap(item.evidence(), 3, 120)));
        }
        out.sort(Comparator.comparingInt(d -> DIMENSION_ORDER.indexOf(d.dimension())));
        return out;
    }

    /** 按公司类型的评分口径加权。同样的表现，大厂看深度、制造业看稳定。 */
    public static double weightedOverall(List<DimensionScore> dimensions, CompanyTier tier) {
        Map<ScoreDimension, Double> weights = Companies.scoreWeights(tier);
        double totalWeight = 0.0;
        double weighted = 0.0;
        for (DimensionScore d : dimensions) {
            double w = weights.getOrDefault(d.dimension(), 0.0);
            totalWeight += w;
            weighted += d.score() * w;
        }
        if (totalWeight <= 0) {
            return 0.0;
        }
        return Double.parseDouble(Text.fixed(weighted / totalWeight, 1));
    }

    /**
     * 批注要能锚回逐字稿。
     *
     * <p>轮次号越界的直接丢：模型偶尔会编一个不存在的轮次，界面上点过去会是空的。
     */
    private static List<TranscriptAnnotation> buildAnnotations(List<AnnotationRaw> raw,
                                                              int validTurns) {
        List<TranscriptAnnotation> out = new ArrayList<>();
        for (AnnotationRaw item : raw) {
            if (item.turnIndex() < 1 || item.turnIndex() > Math.max(1, validTurns)) {
                continue;
            }
            AnnotationKind kind = AnnotationKind.of(item.kind());
            String comment = Text.trim(item.comment(), 120);
            if (kind == null || comment.isEmpty()) {
                continue;
            }
            out.add(new TranscriptAnnotation(item.turnIndex(), kind,
                    Text.trim(item.quote(), 60), comment));
            if (out.size() >= 24) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** 太短的重构没有参考价值，宁可不给。 */
    private static List<AnswerRewrite> buildRewrites(List<RewriteRaw> raw) {
        List<AnswerRewrite> out = new ArrayList<>();
        for (RewriteRaw item : raw) {
            String rewritten = item.rewritten().strip();
            if (rewritten.length() < 40) {
                continue;
            }
            out.add(new AnswerRewrite(Math.max(0, item.questionIndex()),
                    Text.trim(item.question(), 160),
                    Text.cut(item.original().strip(), 800),
                    Text.cut(rewritten, 1500),
                    cap(item.whyBetter(), 4, 50),
                    cap(item.usedAssets(), 6, 40)));
            if (out.size() >= 5) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** 同一知识点只留一条，按严重度排序。 */
    private static List<MistakeItem> buildMistakes(List<MistakeRaw> raw) {
        List<MistakeItem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MistakeRaw item : raw) {
            String point = Text.trim(item.knowledgePoint(), 40);
            String key = point.toLowerCase(Locale.ROOT);
            if (point.isEmpty() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            out.add(new MistakeItem(point, Text.trim(item.topic(), 20),
                    Text.trim(item.question(), 160), Text.trim(item.candidateAnswer(), 80),
                    cap(item.keyPoints(), 5, 40),
                    Labeled.parse(GapSeverity.class, item.severity(), GapSeverity.MAJOR),
                    Text.trim(item.reviewHint(), 100)));
        }
        out.sort(Comparator.comparingInt(m -> m.severity().order()));
        return out.size() <= 12 ? List.copyOf(out) : List.copyOf(out.subList(0, 12));
    }

    /** 没有训练动作的方案等于空话，直接丢掉。 */
    private static List<ImprovementPlan> buildPlans(List<PlanRaw> raw) {
        List<ImprovementPlan> out = new ArrayList<>();
        for (PlanRaw item : raw) {
            String focus = Text.trim(item.focusArea(), 40);
            if (focus.isEmpty()) {
                continue;
            }
            List<DrillItem> drills = new ArrayList<>();
            for (DrillRaw d : item.drills()) {
                if (Text.notBlank(d.action()) && drills.size() < 5) {
                    drills.add(new DrillItem(Text.trim(d.action(), 120),
                            Text.trim(d.why(), 80), Text.trim(d.timeCost(), 20)));
                }
            }
            if (drills.isEmpty()) {
                continue;
            }
            out.add(new ImprovementPlan(focus, Text.trim(item.diagnosis(), 160),
                    Text.trim(item.expectedGain(), 80), List.copyOf(drills),
                    cap(item.resources(), 4, 60), Text.trim(item.nextMockSetup(), 160)));
            if (out.size() >= 3) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<String> cap(List<String> items, int count, int width) {
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (Text.notBlank(item) && out.size() < count) {
                out.add(Text.trim(item, width));
            }
        }
        return List.copyOf(out);
    }
}
