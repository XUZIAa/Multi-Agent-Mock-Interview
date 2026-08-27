package com.interviewer.llm;

import com.interviewer.core.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * 全部提示词。
 *
 * <p>系统指令放在 {@code resources/prompts/*.txt}，不写成 Java 字符串字面量——这些文案
 * 是逐字调过的，缩进、空行、编号（包括 director 里那处重复的「2.」）都影响模型行为。
 * 放进代码里迟早会被格式化工具重排。
 *
 * <p>用户提示词是有条件拼装的，所以留在代码里；但每一段字面量同样不许随手改。
 */
public final class Prompts {

    // ---------- 面试前：简历 / JD / 差距诊断 ----------

    public static final String RESUME_EXTRACT = load("resume_extract");
    public static final String JD_EXTRACT = load("jd_extract");
    public static final String GAP_ANALYSIS = load("gap_analysis");

    // ---------- 面试中 ----------

    public static final String DIRECTOR_SYSTEM = load("director_system");
    public static final String GUARD_SYSTEM = load("guard_system");
    public static final String STAR_SYSTEM = load("star_system");
    public static final String COPILOT_SYSTEM = load("copilot_system");
    public static final String CODE_PROBE_SYSTEM = load("code_probe_system");

    // ---------- 面试后：复盘 ----------

    public static final String REVIEW_SCORE_SYSTEM = load("review_score_system");
    public static final String ANNOTATE_SYSTEM = load("annotate_system");
    public static final String REWRITE_SYSTEM = load("rewrite_system");
    public static final String MISTAKES_SYSTEM = load("mistakes_system");
    public static final String IMPROVEMENT_SYSTEM = load("improvement_system");

    // ---------- 准备阶段 ----------

    public static final String JD_SYNTH = load("jd_synth");
    public static final String BANK_TECH_SYSTEM = load("bank_tech_system");
    public static final String BANK_SOFT_SYSTEM = load("bank_soft_system");
    public static final String CODING_COMPOSE_SYSTEM = load("coding_compose_system");

    private Prompts() {
    }

    private static String load(String name) {
        return PromptResource.load(name);
    }

    // ---------- 差距诊断 ----------

    public static String gapUser(String resumeText, String jdText) {
        return "【简历原文】\n" + Text.cut(resumeText, 12000) + "\n\n"
                + "【目标 JD 原文】\n" + Text.cut(jdText, 6000) + "\n\n"
                + "开始比对分析。";
    }

    // ---------- 导演 ----------

    /**
     * 导演的一轮输入。
     *
     * <p>参数多到必须打包：十二项里每一项都是硬约束的一部分，漏一项模型就会越界。
     * 用具名字段而不是长参数列表，避免调用处传错顺序。
     */
    public record DirectorInput(String personaFocus, String stateDigest, String contextBlock,
                                String lastAnswer, List<String> allowedIntents,
                                String candidateBlock, String phaseHint, boolean interruptAllowed,
                                boolean followUpAllowed, boolean forcePersonality,
                                List<String> expectedSignals, List<SkillScore> skillScoreHistory) {
    }

    /** 同技能点的一次历史评分。 */
    public record SkillScore(String skill, double score) {
    }

    public static String directorUser(DirectorInput in) {
        List<String> parts = new ArrayList<>();
        parts.add("【面试官人设与公司口径】");
        parts.add(in.personaFocus());
        parts.add("");
        parts.add(in.stateDigest());
        if (Text.notBlank(in.contextBlock())) {
            parts.add("");
            parts.add(in.contextBlock());
        }
        parts.add("");
        parts.add("【候选人上一轮回答的完整转写】");
        parts.add(Text.notBlank(in.lastAnswer()) ? in.lastAnswer() : "（尚无回答，这是本场第一次提问）");

        if (in.expectedSignals() != null && !in.expectedSignals().isEmpty()) {
            parts.add("");
            parts.add("【这道题的期望信号｜评分时逐条勾选】");
            parts.add("候选人答到了几个？哪个没答到？是停留在名词还是触及原理？评分以对照这张表为准。");
            in.expectedSignals().forEach(sig -> parts.add("- " + sig));
        }

        parts.add("");
        parts.add("【题库】");
        parts.add(in.candidateBlock());

        List<SkillScore> history = in.skillScoreHistory();
        if (history != null && !history.isEmpty()) {
            // 仅保留最近 3 条已评分的同技能点记录，避免刷屏
            List<SkillScore> recent = history.subList(Math.max(0, history.size() - 3), history.size());
            parts.add("");
            parts.add("【同技能点历史评分｜用于保持前后一致】");
            parts.add("你之前对这个（或相邻）技能点给过的评分如下。本轮分数必须与它们保持合理连续，"
                    + "不要突然上下跳变，除非候选人回答质量本身出现了显著变化。");
            recent.forEach(s -> parts.add("- " + s.skill() + ": " + Text.fixed(s.score(), 2)));
        }

        parts.add("");
        parts.add("【本轮硬约束】");
        parts.add("- 允许的 intent：" + String.join(", ", in.allowedIntents()));
        parts.add("- 追问额度：" + (in.followUpAllowed()
                ? "还可以继续追问" : "追问深度已到上限，必须换新问题或切阶段"));
        parts.add("- 打断额度：" + (in.interruptAllowed()
                ? "可以打断" : "本阶段打断额度已用完，不得设 should_interrupt"));
        parts.add("- 推进指示：" + in.phaseHint());
        if (in.forcePersonality()) {
            parts.add("- 本轮请穿插一个性格或价值观问题，并把 is_personality 设为 true。");
        }
        parts.add("");
        parts.add("给出本轮指令。");
        return String.join("\n", parts);
    }

    // ---------- 守卫 ----------

    public static String guardUser(String personaSummary, String spoken) {
        return "【面试官人设摘要】\n" + personaSummary + "\n\n"
                + "【面试官刚说的话】\n" + spoken + "\n\n"
                + "判定。";
    }

    // ---------- STAR ----------

    public static String starUser(String question, String answer) {
        return "【面试官的问题】\n" + question + "\n\n"
                + "【候选人的回答】\n" + answer + "\n\n"
                + "判定 STAR 完整度。";
    }

    // ---------- 提词器 ----------

    public static String copilotUser(String question, String partialAnswer, String resumeDigest) {
        return "【候选人简历要点】\n" + Text.cut(resumeDigest, 1200) + "\n\n"
                + "【面试官刚问的问题】\n" + question + "\n\n"
                + "【候选人已经说出的部分】\n"
                + (Text.notBlank(partialAnswer) ? partialAnswer : "（还没开口）") + "\n\n"
                + "给提示。";
    }

    // ---------- 代码追问 ----------

    public static String codeProbeUser(String language, String source, String problem) {
        return "【题目/上下文】\n"
                + (Text.notBlank(problem) ? problem : "（面试官口述题目，未记录）") + "\n\n"
                + "【候选人代码（" + language + "）】\n```" + language + "\n"
                + Text.cut(source, 8000) + "\n```\n\n"
                + "给出追问。";
    }

    // ---------- 复盘 ----------

    public static String reviewUser(String personaName, String jdDigest, String resumeDigest,
                                    String transcript, String codingSummary,
                                    String prosodySummary) {
        List<String> parts = new ArrayList<>();
        parts.add("【面试官人设】" + personaName);
        if (Text.notBlank(jdDigest)) {
            parts.add("【目标岗位】\n" + jdDigest);
        }
        if (Text.notBlank(resumeDigest)) {
            parts.add("【候选人简历要点】\n" + resumeDigest);
        }
        if (Text.notBlank(codingSummary)) {
            parts.add("【编码环节】\n" + codingSummary);
        }
        if (Text.notBlank(prosodySummary)) {
            parts.add("【客观语音指标｜已由程序测得，不要改动这些数字】\n" + prosodySummary);
        }
        parts.add("【完整逐字稿】\n" + transcript);
        return String.join("\n\n", parts);
    }

    // ---------- JD 合成 ----------

    public static String jdSynthUser(String title, String tierLabel, String tierFlavor,
                                     String levelLabel, String extra) {
        List<String> parts = new ArrayList<>();
        parts.add("岗位名称：" + title);
        parts.add("公司类型：" + tierLabel);
        parts.add("该类型公司的 JD 惯例：" + tierFlavor);
        parts.add("目标级别：" + levelLabel);
        if (Text.notBlank(extra)) {
            parts.add("用户补充要求：" + extra.strip());
        }
        parts.add("生成岗位描述。");
        return String.join("\n", parts);
    }

    // ---------- 题库构建 ----------

    public static String bankUser(String jdDigest, String resumeDigest, String companyBlock,
                                  String levelExpectation, String gapDigest,
                                  boolean codingEnabled, int minutes) {
        List<String> parts = new ArrayList<>();
        parts.add(companyBlock);
        parts.add("");
        parts.add("【级别口径】" + levelExpectation);
        if (Text.notBlank(jdDigest)) {
            parts.add("");
            parts.add("【目标 JD】");
            parts.add(jdDigest);
        }
        if (Text.notBlank(resumeDigest)) {
            parts.add("");
            parts.add("【候选人简历】");
            parts.add(resumeDigest);
        }
        if (Text.notBlank(gapDigest)) {
            parts.add("");
            parts.add("【面试前诊断】");
            parts.add(gapDigest);
        }
        parts.add("");
        parts.add("【本场时长】" + minutes + " 分钟｜编码环节："
                + (codingEnabled ? "开启" : "关闭，不要出编码题"));
        parts.add("");
        parts.add("开始出题。");
        return String.join("\n", parts);
    }

    // ---------- 专项提升 ----------

    public static String improvementUser(String headline, String dimensionLines,
                                         String abandonedLines, String mistakeLines,
                                         String jdDigest) {
        List<String> parts = new ArrayList<>();
        parts.add("【本场结论】" + headline);
        parts.add("");
        parts.add("【维度得分】");
        parts.add(dimensionLines);
        if (Text.notBlank(abandonedLines)) {
            parts.add("");
            parts.add("【面试中被放弃深挖的技能点｜最硬的短板】");
            parts.add(abandonedLines);
        }
        if (Text.notBlank(mistakeLines)) {
            parts.add("");
            parts.add("【本场答错的知识点】");
            parts.add(mistakeLines);
        }
        if (Text.notBlank(jdDigest)) {
            parts.add("");
            parts.add("【目标岗位要求】");
            parts.add(jdDigest);
        }
        parts.add("");
        parts.add("给出专项提升方案。");
        return String.join("\n", parts);
    }

    // ---------- 编码题 ----------

    public static String codingComposeUser(String skill, String jobTitle,
                                           String levelExpectation, int minutes) {
        List<String> parts = new ArrayList<>();
        parts.add("【目标岗位】" + (Text.notBlank(jobTitle) ? jobTitle : "技术岗"));
        parts.add("【级别口径】" + levelExpectation);
        parts.add("【本场时长】" + minutes + " 分钟");
        if (Text.notBlank(skill)) {
            parts.add("【希望考察的方向】" + skill);
        }
        parts.add("出一道编码题。");
        return String.join("\n", parts);
    }
}
