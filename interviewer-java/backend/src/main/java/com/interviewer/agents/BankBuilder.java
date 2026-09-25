package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.provider.ModelTraits;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.Depth;
import com.interviewer.core.type.JobLevel;
import com.interviewer.core.type.Labeled;
import com.interviewer.core.type.QuestionSource;
import com.interviewer.data.corpus.CorpusStore;
import com.interviewer.data.corpus.RealQuestion;
import com.interviewer.domain.bank.BankQuestion;
import com.interviewer.domain.bank.QuestionBank;
import com.interviewer.domain.company.Companies;
import com.interviewer.domain.resume.GapReport;
import com.interviewer.domain.resume.JobDescription;
import com.interviewer.domain.resume.ResumeProfile;
import com.interviewer.domain.resume.SkillGap;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 面试前构建题库。技术题与软性题分两次生成，避免单次输出过长而截断。
 *
 * <p>面试中问出的每个新问题都必须来自这里。让导演自由生成题目，问出来的东西对不上 JD
 * 也对不上简历。
 */
@Component
public class BankBuilder extends Agent {

    private static final Logger log = LoggerFactory.getLogger(BankBuilder.class);

    /** 真题最多补这么多条，不能把贴合简历的项目题挤掉。 */
    private static final int REAL_QUESTION_QUOTA = 10;

    /** 缺省领域名。同一领域的字符串必须完全一致，导演靠它判断「换领域」。 */
    private static final Map<QuestionSource, String> DEFAULT_DOMAIN = Map.of(
            QuestionSource.BEHAVIORAL, "行为与价值观",
            QuestionSource.CODING, "编码",
            QuestionSource.RESUME_PROJECT, "项目经历");

    private final CorpusStore corpus;

    public BankBuilder(LlmRouter router, CorpusStore corpus) {
        super(router);
        this.corpus = corpus;
    }

    @Override
    protected String role() {
        return Providers.ROLE_ANALYST;
    }

    /** 出题进度回调。两次调用各自报进度，否则界面停在同一个百分比让人以为卡死。 */
    public interface StepHook {
        void step(String label, int percent);
    }

    record ItemRaw(@JsonProperty("text") @JsonAlias({"name", "question", "content"}) String text,
                   @JsonProperty("skill") String skill,
                   @JsonProperty("domain") String domain,
                   @JsonProperty("depth") int depth,
                   @JsonProperty("source") String source,
                   @JsonProperty("project_ref") String projectRef,
                   @JsonProperty("jd_ref") String jdRef,
                   @JsonProperty("follow_ups") List<String> followUps,
                   @JsonProperty("expected_signals") List<String> expectedSignals,
                   @JsonProperty("must_ask") boolean mustAsk) {

        ItemRaw(String text, String skill, String domain, int depth, String source,
                String projectRef, String jdRef, List<String> followUps,
                List<String> expectedSignals, boolean mustAsk) {
            this.text = Text.safe(text);
            this.skill = Text.safe(skill);
            this.domain = Text.safe(domain);
            this.depth = depth <= 0 ? 1 : depth;
            this.source = Text.safe(source).isEmpty() ? "fundamental" : Text.safe(source);
            this.projectRef = Text.safe(projectRef);
            this.jdRef = Text.safe(jdRef);
            this.followUps = followUps == null ? List.of() : List.copyOf(followUps);
            this.expectedSignals = expectedSignals == null
                    ? List.of() : List.copyOf(expectedSignals);
            this.mustAsk = mustAsk;
        }
    }

    record BankRaw(@JsonProperty("questions") List<ItemRaw> questions) {

        BankRaw(List<ItemRaw> questions) {
            this.questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    public QuestionBank build(ResumeProfile resume, JobDescription job, GapReport gap,
                              CompanyTier tier, JobLevel level, int minutes,
                              boolean codingEnabled, StepHook hook) {
        String context = Prompts.bankUser(
                job.compact(1600), resume.compact(2200),
                Companies.of(tier).guidanceBlock(),
                Companies.levelExpectation(tier, level),
                gap == null ? "" : gap.compact(900),
                codingEnabled, minutes);

        String slow = ModelTraits.of(client().model()).reasoning()
                ? "（推理模型逐字思考，这一步可能要数分钟）" : "";
        step(hook, "正在出技术题 1/2" + slow, 40);
        BankRaw tech = generate(Prompts.BANK_TECH_SYSTEM, context, 9000);
        step(hook, "正在出行为题与编码题 2/2" + slow, 62);
        BankRaw soft = generate(Prompts.BANK_SOFT_SYSTEM, context, 5000);
        step(hook, "正在整理题库", 74);

        List<BankQuestion> questions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int nextId = 1;
        List<ItemRaw> merged = new ArrayList<>(tech.questions());
        merged.addAll(soft.questions());
        for (ItemRaw item : merged) {
            BankQuestion built = toQuestion(item, nextId, codingEnabled);
            if (built == null) {
                continue;
            }
            // 题面前 40 字当指纹：模型两次调用之间会重复出题
            String fingerprint = fingerprint(built.text());
            if (seen.contains(fingerprint)) {
                continue;
            }
            seen.add(fingerprint);
            questions.add(built);
            nextId++;
        }

        int real = appendRealQuestions(questions, nextId, seen, resume, job);

        if (gap != null) {
            applyMustAsk(questions, gap);
        }
        QuestionBank bank = new QuestionBank(questions);
        log.info("题库构建完成：{} 道（模型 {} + 真题 {}），领域 {}，必问 {}",
                questions.size(), questions.size() - real, real,
                String.join("/", head(bank.domains(), 8)),
                bank.pendingMustAsk(Set.of()).size());
        return bank;
    }

    private BankRaw generate(String instruction, String context, int maxTokens) {
        return client().structured(
                List.of(new SystemMessage(instruction), new UserMessage(context)),
                BankRaw.class, 0.55, maxTokens, 1);
    }

    private static void step(StepHook hook, String label, int percent) {
        if (hook != null) {
            hook.step(label, percent);
        }
    }

    /**
     * 把检索到的真实高频题追加进题库，返回实际加入的条数。
     *
     * <p>只补 fundamental 一路，不动模型出的项目题与 JD 题：项目题贴合简历，是这套东西的
     * 立身之本，不能被通用八股挤掉。检索不到就一条不加，题库与没有语料时完全一致。
     */
    private int appendRealQuestions(List<BankQuestion> questions, int nextId, Set<String> seen,
                                    ResumeProfile resume, JobDescription job) {
        List<String> fragments = new ArrayList<>();
        fragments.addAll(job.getMustHave());
        fragments.addAll(job.getNiceToHave());
        fragments.addAll(resume.getSkills());
        fragments.add(job.getTitle());

        List<RealQuestion> hits;
        try {
            hits = corpus.search(fragments, job.getTitle());
        } catch (Exception e) {
            log.warn("真题检索失败，题库按模型结果使用: {}", e.getMessage());
            return 0;
        }
        if (hits.isEmpty()) {
            return 0;
        }

        int added = 0;
        for (RealQuestion hit : hits) {
            if (added >= REAL_QUESTION_QUOTA) {
                break;
            }
            String text = Text.trim(hit.text(), 160);
            if (text.length() < 6) {
                continue;
            }
            String fingerprint = fingerprint(text);
            if (seen.contains(fingerprint)) {
                continue;
            }
            seen.add(fingerprint);
            // 语料的分类名当领域用，同类题的 domain 保持一致，导演靠它判断换领域
            String domain = corpusDomain(hit.category());
            questions.add(new BankQuestion(nextId + added, text, domain, domain,
                    realDepth(hit.sources()), QuestionSource.FUNDAMENTAL,
                    "", "", List.of(), List.of(), false));
            added++;
        }
        return added;
    }

    private static String corpusDomain(String category) {
        String tail = Text.safe(category);
        int slash = tail.lastIndexOf('/');
        if (slash >= 0) {
            tail = tail.substring(slash + 1);
        }
        int sep = tail.indexOf('、');
        if (sep >= 0) {
            tail = tail.substring(0, sep);
        }
        tail = tail.strip();
        return tail.isEmpty() ? "基础" : tail;
    }

    /** 频次越高越是必考的概念层，低频的多半是现场深挖出来的追问。 */
    private static int realDepth(int sources) {
        if (sources >= 40) {
            return 1;
        }
        return sources >= 10 ? 2 : 3;
    }

    private static BankQuestion toQuestion(ItemRaw item, int questionId, boolean codingEnabled) {
        String text = Text.trim(item.text(), 160);
        if (text.length() < 6) {
            return null;
        }
        QuestionSource source = Labeled.parse(QuestionSource.class, item.source(),
                QuestionSource.FUNDAMENTAL);
        if (source == QuestionSource.CODING && !codingEnabled) {
            return null;
        }
        String skill = Text.trim(item.skill(), 40);
        if (skill.isEmpty()) {
            skill = Text.trim(item.domain(), 40);
        }
        if (skill.isEmpty()) {
            skill = "综合";
        }
        String domain = Text.trim(item.domain(), 30);
        if (domain.isEmpty()) {
            domain = DEFAULT_DOMAIN.getOrDefault(source, "综合");
        }
        return new BankQuestion(questionId, text, skill, domain,
                Depth.clamp(item.depth()), source,
                Text.trim(item.projectRef(), 40), Text.trim(item.jdRef(), 60),
                cap(item.followUps(), 3, 40), cap(item.expectedSignals(), 4, 50),
                item.mustAsk());
    }

    /**
     * 诊断出的致命缺口对应的题目，强制标为必问。
     *
     * <p>另外把必考清单里的 JD 入门题也标上：那些是「一定会被问到」的，漏考等于白练。
     */
    private static void applyMustAsk(List<BankQuestion> questions, GapReport gap) {
        Set<String> blockers = new LinkedHashSet<>();
        for (SkillGap g : gap.blockers()) {
            blockers.add(g.skill().strip().toLowerCase(Locale.ROOT));
        }
        Set<String> focus = new LinkedHashSet<>();
        for (String s : gap.getFocusSkills()) {
            focus.add(Text.safe(s).strip().toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < questions.size(); i++) {
            BankQuestion q = questions.get(i);
            String key = q.skill().strip().toLowerCase(Locale.ROOT);
            boolean focusEntry = focus.contains(key)
                    && q.source() == QuestionSource.JD_REQUIREMENT
                    && q.depth() <= 2;
            if (blockers.contains(key) || focusEntry) {
                questions.set(i, q.withMustAsk(true));
            }
        }
    }

    private static String fingerprint(String text) {
        String head = text.length() > 40 ? text.substring(0, 40) : text;
        return head.toLowerCase(Locale.ROOT);
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

    private static <T> List<T> head(List<T> list, int limit) {
        return list.size() <= limit ? list : list.subList(0, limit);
    }
}
