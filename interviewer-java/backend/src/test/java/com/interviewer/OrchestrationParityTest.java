package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.core.config.OrchestrationSettings;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.JobLevel;
import com.interviewer.core.type.QuestionSource;
import com.interviewer.core.type.Speaker;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.bank.BankQuestion;
import com.interviewer.domain.bank.QuestionBank;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.Plans;
import com.interviewer.domain.persona.BuiltinPersonas;
import com.interviewer.domain.persona.PersonaContract;
import com.interviewer.domain.turn.TurnPlan;
import com.interviewer.orchestration.Anchor;
import com.interviewer.orchestration.Policy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 编排层与 Python 原版逐字比对。
 *
 * <p>这是整套系统最该锁住的一处：{@code digest()} 是每轮注入模型的权威进度、
 * {@code buildInstructions()} 是人格锚点全文、{@code planTurn()} 是模型的活动边界。
 * 三者任何一处漂移都会改变面试行为，而且不会报错。
 *
 * <p>基准按同一串状态变更在两边各跑一遍：开场 → 暖场有答案 → 答得好加深 →
 * 答不上同层换角度 → 连续低分放弃 → 接近收尾线 → 必须收尾。
 */
class OrchestrationParityTest {

    @Test
    void snapshotsMatchPythonBaseline() throws Exception {
        JsonNode root = Parity.load("orchestration");
        Assumptions.assumeTrue(root != null, "没有 orchestration 基准文件");

        InterviewState state = newState();
        OrchestrationSettings settings = new OrchestrationSettings();
        JsonNode steps = root.get("steps");
        int cursor = 0;

        state.enterPhase(InterviewPhase.WARMUP);
        check(steps.get(cursor++), state, settings);

        state.openQuestion(TurnIntent.ASK_NEW, "自我介绍", "", "候选人经历", 1, null, false);
        state.appendTurn(Speaker.INTERVIEWER, "介绍下自己", 0, 3000, null, false);
        state.appendTurn(Speaker.CANDIDATE, "我做了三年后端，主要用 Java 和 Redis",
                3000, 9000, null, false);
        state.setElapsedMs(60000);
        check(steps.get(cursor++), state, settings);

        state.enterPhase(InterviewPhase.TECH_DEPTH);
        state.setElapsedMs(200000);
        state.openQuestion(TurnIntent.ASK_NEW, "问 Redis", "Redis", "缓存", 1, 1, false);
        state.appendTurn(Speaker.CANDIDATE, "我们用 SETNX 加过期时间", 200000, 8000, null, false);
        state.observeAnswer(0.9);
        check(steps.get(cursor++), state, settings);

        state.openQuestion(TurnIntent.FOLLOW_UP, "追问续期", "Redis", "缓存", 2, null, false);
        state.appendTurn(Speaker.CANDIDATE, "不太清楚", 210000, 2000, null, false);
        state.observeAnswer(0.1);
        check(steps.get(cursor++), state, settings);

        state.appendTurn(Speaker.CANDIDATE, "还是不会", 215000, 2000, null, false);
        state.observeAnswer(0.05);
        check(steps.get(cursor++), state, settings);

        state.setElapsedMs(1750000);
        check(steps.get(cursor++), state, settings);

        state.setElapsedMs(1790000);
        check(steps.get(cursor++), state, settings);

        assertEquals(steps.size(), cursor, "快照数量对不上");
    }

    private static void check(JsonNode want, InterviewState state, OrchestrationSettings settings) {
        String tag = want.get("tag").asText();
        assertEquals(want.get("phase").asText(), state.getPhase().value(), tag + " phase");
        assertEquals(want.get("digest").asText(), state.digest(), tag + " digest");
        assertEquals(want.get("context").asText(), state.contextBlock(), tag + " contextBlock");
        assertEquals(want.get("instructions").asText(), Anchor.buildInstructions(state),
                tag + " buildInstructions");

        TurnPlan plan = Policy.planTurn(state, settings);
        JsonNode wp = want.get("plan");
        assertEquals(texts(wp.get("intents")), values(plan.allowedIntents()), tag + " intents");
        assertEquals(ints(wp.get("candidates")), ids(plan.candidates()), tag + " candidates");
        assertEquals(wp.get("depth_action").isNull() ? null : wp.get("depth_action").asText(),
                plan.depthAction() == null ? null : plan.depthAction().value(),
                tag + " depthAction");
        assertEquals(wp.get("phase_hint").asText(), plan.phaseHint(), tag + " phaseHint");
        assertEquals(wp.get("interrupt").asBoolean(), plan.interruptAllowed(), tag + " interrupt");
        assertEquals(wp.get("follow_up").asBoolean(), plan.followUpAllowed(), tag + " followUp");
        assertEquals(wp.get("personality").asBoolean(), plan.forcePersonality(),
                tag + " forcePersonality");
        assertEquals(wp.get("must_close").asBoolean(), plan.mustClose(), tag + " mustClose");
        assertEquals(wp.get("prefer").isNull() ? null : wp.get("prefer").asText(),
                plan.preferDomain(), tag + " preferDomain");
        assertEquals(texts(wp.get("avoid")), plan.avoidDomains(), tag + " avoidDomains");
        assertEquals(wp.get("block").asText(), plan.candidateBlock(), tag + " candidateBlock");
    }

    private static InterviewState newState() {
        PersonaContract persona = BuiltinPersonas.all().stream()
                .filter(p -> "大厂技术面试官".equals(p.getName())).findFirst().orElseThrow();

        List<BankQuestion> qs = new ArrayList<>();
        int id = 0;
        List<QuestionSource> sources = List.of(QuestionSource.JD_REQUIREMENT,
                QuestionSource.RESUME_PROJECT, QuestionSource.FUNDAMENTAL,
                QuestionSource.BEHAVIORAL);
        String[][] domains = {{"缓存", "Redis"}, {"数据库", "MySQL"}};
        for (QuestionSource src : sources) {
            for (String[] pair : domains) {
                for (int d = 1; d <= 3; d++) {
                    id++;
                    qs.add(new BankQuestion(id,
                            pair[1] + " 第" + d + "层 " + src.value() + " 的问题是什么",
                            pair[1], pair[0], d, src,
                            src == QuestionSource.RESUME_PROJECT ? "订单系统" : "", "",
                            List.of(), List.of("信号" + d + "a", "信号" + d + "b"),
                            id % 9 == 0));
                }
            }
        }

        InterviewState state = new InterviewState();
        state.setSessionId(7);
        state.setPersona(persona);
        state.setPlan(Plans.build(persona, 30, false, null));
        state.setBank(new QuestionBank(qs));
        state.setCompanyTier(CompanyTier.BIG_TECH);
        state.setJobLevel(JobLevel.SENIOR);
        state.setJobTitle("高级后端工程师");
        state.setResumeDigest("候选人：张三\n技能栈：Java、Redis");
        state.setJdDigest("硬性要求：Redis、MySQL");
        state.setGapDigest("盲区：Redis 续期");
        return state;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    private static List<String> values(List<TurnIntent> intents) {
        List<String> out = new ArrayList<>();
        intents.forEach(i -> out.add(i.value()));
        return out;
    }

    private static List<Integer> ids(List<BankQuestion> questions) {
        List<Integer> out = new ArrayList<>();
        questions.forEach(q -> out.add(q.id()));
        return out;
    }
}
