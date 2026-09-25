package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.domain.bank.DepthAction;
import com.interviewer.domain.bank.SkillProgress;
import com.interviewer.domain.interview.InterviewPlan;
import com.interviewer.domain.interview.PhaseSlot;
import com.interviewer.domain.interview.Plans;
import com.interviewer.domain.persona.BuiltinPersonas;
import com.interviewer.domain.persona.PersonaContract;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 确定性规则与 Python 原版逐值比对。
 *
 * <p>面试排期与深度阶梯是这套系统的核心：前者决定时间花在哪，后者决定「往下深挖还是
 * 换领域」。两者都不经过模型，所以必须完全一致——差一毫秒或差一档都是行为变化。
 */
class DomainParityTest {

    @Test
    void planMatchesPythonBaseline() throws Exception {
        JsonNode root = Parity.load("domain");
        Assumptions.assumeTrue(root != null, "没有 domain 基准文件");

        Map<String, PersonaContract> byName = new LinkedHashMap<>();
        BuiltinPersonas.all().forEach(p -> byName.put(p.getName(), p));

        JsonNode plans = root.get("plans");
        assertEquals(88, plans.size(), "排期组合数量对不上");

        for (JsonNode want : plans) {
            String name = want.get("persona").asText();
            int minutes = want.get("minutes").asInt();
            boolean coding = want.get("coding").asBoolean();
            String tag = name + "/" + minutes + "min/coding=" + coding;

            PersonaContract persona = byName.get(name);
            assertNotNull(persona, "缺少人设 " + name);
            InterviewPlan actual = Plans.build(persona, minutes, coding, null);

            assertEquals(want.get("total_ms").asLong(), actual.totalMs(), tag + " total_ms");
            assertEquals(want.get("closing_ms").asLong(), actual.closingMs(), tag + " closing_ms");

            JsonNode slots = want.get("slots");
            assertEquals(slots.size(), actual.slots().size(), tag + " 环节数");
            for (int i = 0; i < slots.size(); i++) {
                JsonNode w = slots.get(i);
                PhaseSlot a = actual.slots().get(i);
                String slotTag = tag + " slot[" + i + "]";
                assertEquals(w.get("phase").asText(), a.phase().value(), slotTag + " phase");
                assertEquals(w.get("budget_ms").asLong(), a.budgetMs(), slotTag + " budget_ms");
                assertEquals(w.get("min_questions").asInt(), a.minQuestions(),
                        slotTag + " min_questions");
            }
        }
    }

    @Test
    void depthLadderMatchesPythonBaseline() throws Exception {
        JsonNode root = Parity.load("domain");
        Assumptions.assumeTrue(root != null, "没有 domain 基准文件");

        for (JsonNode run : root.get("ladder")) {
            SkillProgress sp = new SkillProgress("Redis", "缓存");
            JsonNode steps = run.get("steps");
            for (int i = 0; i < steps.size(); i++) {
                JsonNode want = steps.get(i);
                Double quality = want.get("q").isNull() ? null : want.get("q").asDouble();
                DepthAction action = sp.observe(quality);
                String tag = "seq" + run.get("seq") + " step" + i;

                assertEquals(want.get("action").asText(), action.value(), tag + " action");
                assertEquals(want.get("depth_reached").asInt(), sp.getDepthReached(),
                        tag + " depth_reached");
                assertEquals(want.get("attempts").asInt(), sp.getAttempts(), tag + " attempts");
                assertEquals(want.get("attempts_at_depth").asInt(), sp.getAttemptsAtDepth(),
                        tag + " attempts_at_depth");
                assertEquals(want.get("low_streak").asInt(), sp.getLowStreak(), tag + " low_streak");
                assertEquals(want.get("best").asDouble(), sp.getBestQuality(), 1e-9, tag + " best");
                assertEquals(want.get("exhausted").asBoolean(), sp.isExhausted(), tag + " exhausted");
                assertEquals(want.get("abandoned_at").asInt(), sp.getAbandonedAtDepth(),
                        tag + " abandoned_at_depth");
                assertEquals(want.get("next_depth").asInt(), sp.nextDepth(), tag + " next_depth");
                assertEquals(want.get("summary").asText(), sp.summary(), tag + " summary");
            }
        }
    }
}
