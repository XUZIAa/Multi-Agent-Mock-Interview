package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.llm.Prompts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 提示词与 Python 原版逐字节比对。
 *
 * <p>系统指令比 sha256：这些文案决定模型行为，任何一个字符的漂移都是行为变化，
 * 而漂移通常来自换行转换或格式化工具，肉眼看不出来。
 *
 * <p>模板输出比全文：拼装逻辑里有一堆条件分支，基准覆盖了有值/无值两侧。
 */
class PromptParityTest {

    @Test
    void systemPromptsMatchPythonDigest() throws Exception {
        JsonNode expected = Parity.load("prompts");
        Assumptions.assumeTrue(expected != null, "没有 prompts 基准文件");

        Map<String, String> actual = new HashMap<>();
        actual.put("RESUME_EXTRACT", Prompts.RESUME_EXTRACT);
        actual.put("JD_EXTRACT", Prompts.JD_EXTRACT);
        actual.put("GAP_ANALYSIS", Prompts.GAP_ANALYSIS);
        actual.put("DIRECTOR_SYSTEM", Prompts.DIRECTOR_SYSTEM);
        actual.put("GUARD_SYSTEM", Prompts.GUARD_SYSTEM);
        actual.put("STAR_SYSTEM", Prompts.STAR_SYSTEM);
        actual.put("COPILOT_SYSTEM", Prompts.COPILOT_SYSTEM);
        actual.put("CODE_PROBE_SYSTEM", Prompts.CODE_PROBE_SYSTEM);
        actual.put("REVIEW_SCORE_SYSTEM", Prompts.REVIEW_SCORE_SYSTEM);
        actual.put("ANNOTATE_SYSTEM", Prompts.ANNOTATE_SYSTEM);
        actual.put("REWRITE_SYSTEM", Prompts.REWRITE_SYSTEM);
        actual.put("MISTAKES_SYSTEM", Prompts.MISTAKES_SYSTEM);
        actual.put("IMPROVEMENT_SYSTEM", Prompts.IMPROVEMENT_SYSTEM);
        actual.put("JD_SYNTH", Prompts.JD_SYNTH);
        actual.put("BANK_TECH_SYSTEM", Prompts.BANK_TECH_SYSTEM);
        actual.put("BANK_SOFT_SYSTEM", Prompts.BANK_SOFT_SYSTEM);
        actual.put("CODING_COMPOSE_SYSTEM", Prompts.CODING_COMPOSE_SYSTEM);

        assertEquals(expected.size(), actual.size(), "系统提示词数量不一致");

        List<String> names = new ArrayList<>();
        expected.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            String text = actual.get(name);
            assertFalse(text == null || text.isBlank(), "提示词为空: " + name);
            JsonNode want = expected.get(name);
            assertEquals(want.get("chars").asInt(), text.length(), name + " 字符数");
            assertEquals(want.get("sha256").asText(), sha256(text), name + " sha256");
            assertFalse(text.endsWith("\n"), name + " 不应有尾换行");
        }
    }

    @Test
    void templateOutputMatchesPythonBaseline() throws Exception {
        JsonNode expected = Parity.load("prompt-templates");
        Assumptions.assumeTrue(expected != null, "没有 prompt-templates 基准文件");

        Map<String, String> actual = buildAll();
        assertEquals(expected.size(), actual.size(), "模板用例数量不一致");

        for (JsonNode want : expected) {
            String name = want.get("name").asText();
            assertEquals(want.get("text").asText(), actual.get(name), "模板输出不一致: " + name);
        }
    }

    /** 输入与 Python 导出脚本一一对应，改动必须两边同步。 */
    private static Map<String, String> buildAll() {
        Map<String, String> out = new HashMap<>();

        out.put("gap", Prompts.gapUser("简历原文A".repeat(10), "JD原文B".repeat(10)));

        List<String> intents = List.of("ask_new", "close");
        String candidateBlock = "可选的新问题：\n- [1] D1 缓存/Redis｜题面";
        out.put("director_min", Prompts.directorUser(new Prompts.DirectorInput(
                "人设焦点段\n第二行", "【当前进度】\n- 第 3 轮", "", "", intents,
                candidateBlock, "加深一层", false, true, false, null, null)));
        out.put("director_full", Prompts.directorUser(new Prompts.DirectorInput(
                "人设焦点段\n第二行", "【当前进度】\n- 第 3 轮", "【本场设定】目标岗位：后端",
                "我用了 Redis SETNX", intents, candidateBlock, "加深一层", true, false, true,
                List.of("过期续期", "Redlock"),
                List.of(new Prompts.SkillScore("Redis", 0.4),
                        new Prompts.SkillScore("Redis", 0.755),
                        new Prompts.SkillScore("Redis", 0.9),
                        new Prompts.SkillScore("MySQL", 0.5)))));
        out.put("director_signals_only", Prompts.directorUser(new Prompts.DirectorInput(
                "人设焦点段\n第二行", "【当前进度】\n- 第 3 轮", "", "", intents,
                candidateBlock, "加深一层", false, true, false, List.of("信号1"), null)));
        out.put("director_history_short", Prompts.directorUser(new Prompts.DirectorInput(
                "人设焦点段\n第二行", "【当前进度】\n- 第 3 轮", "", "", intents,
                candidateBlock, "加深一层", false, true, false, null,
                List.of(new Prompts.SkillScore("JUC", 0.125)))));

        out.put("guard", Prompts.guardUser("暴躁 CTO｜冷硬", "我是一个AI助手"));
        out.put("star", Prompts.starUser("讲一次冲突", "我们团队当时…"));
        out.put("copilot_empty", Prompts.copilotUser("Redis 怎么做锁", "", "简历要点C".repeat(20)));
        out.put("copilot_partial", Prompts.copilotUser("Redis 怎么做锁", "我先说下背景", "短简历"));
        out.put("code_probe", Prompts.codeProbeUser("python", "print(1)\n".repeat(5), ""));
        out.put("code_probe_with_problem",
                Prompts.codeProbeUser("javascript", "console.log(1)", "两数之和"));

        out.put("review_min", Prompts.reviewUser("暴躁 CTO", "", "", "#1 面试官：你好", "", ""));
        out.put("review_full", Prompts.reviewUser("大厂技术面试官", "JD摘要", "简历摘要",
                "#1 面试官：你好\n#2 我：你好", "提交了 python 代码", "语速 240 字/分"));

        out.put("jd_synth_min", Prompts.jdSynthUser("Java 后端", "互联网大厂",
                "强调技术栈深度", "中级（3-5 年）", ""));
        out.put("jd_synth_extra", Prompts.jdSynthUser("Java 后端", "互联网大厂",
                "强调技术栈深度", "中级（3-5 年）", "  要求 K8s  "));

        out.put("bank_min", Prompts.bankUser("", "", "【公司类型】大厂",
                "考察独立负责子系统", "", false, 30));
        out.put("bank_full", Prompts.bankUser("JD摘要", "简历摘要", "【公司类型】大厂\n【口径】追原理",
                "考察架构设计", "诊断结论", true, 45));

        out.put("improve_min", Prompts.improvementUser("暂不通过", "- 技术深度 52", "", "", ""));
        out.put("improve_full", Prompts.improvementUser("勉强通过", "- 技术深度 66\n- 表达 70",
                "- Redis(D2)", "- 分布式锁续期", "JD摘要"));

        out.put("coding_min", Prompts.codingComposeUser("", "", "中级", 30));
        out.put("coding_full", Prompts.codingComposeUser("哈希表", "Java 后端", "高级", 45));
        return out;
    }

    private static String sha256(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
