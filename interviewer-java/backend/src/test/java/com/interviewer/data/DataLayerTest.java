package com.interviewer.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.interviewer.core.type.GapSeverity;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.core.type.SessionStatus;
import com.interviewer.core.type.Speaker;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.data.dto.GlobalStats;
import com.interviewer.data.dto.SessionSummary;
import com.interviewer.data.dto.StoredMistake;
import com.interviewer.data.dto.StoredResume;
import com.interviewer.data.dto.TrendPoint;
import com.interviewer.data.repository.LibraryRepository;
import com.interviewer.data.repository.PersonaRepository;
import com.interviewer.data.repository.ReviewRepository;
import com.interviewer.data.repository.SessionRepository;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.Plans;
import com.interviewer.domain.interview.TurnRecord;
import com.interviewer.domain.persona.BuiltinPersonas;
import com.interviewer.domain.persona.PersonaContract;
import com.interviewer.domain.resume.ResumeProfile;
import com.interviewer.domain.review.DimensionScore;
import com.interviewer.domain.review.MistakeItem;
import com.interviewer.domain.review.ReviewReport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 持久层集成测试。真建库、真读写。
 *
 * <p>SQL 写错编译器不会报，upsert 的冲突列写错、TypeHandler 的时间格式对不上、
 * 聚合口径算错——这些只有真跑一遍才知道。数据目录由 surefire 指到 target 下。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataLayerTest {

    @Autowired
    private PersonaRepository personas;
    @Autowired
    private LibraryRepository library;
    @Autowired
    private SessionRepository sessions;
    @Autowired
    private ReviewRepository reviews;

    @Test
    @Order(1)
    void timestampRoundTripsInSqlAlchemyFormat() {
        LocalDateTime value = LocalDateTime.of(2026, 8, 26, 12, 34, 56, 789_012_000);
        String text = UtcStamp.format(value);
        assertEquals("2026-08-26 12:34:56.789012", text, "必须与 SQLAlchemy 的存储格式一致");
        assertEquals(value, UtcStamp.parse(text));
        // 老库里可能出现的几种变体都要认下来
        assertEquals(LocalDateTime.of(2026, 8, 26, 12, 34, 56),
                UtcStamp.parse("2026-08-26 12:34:56"));
        assertEquals(LocalDateTime.of(2026, 8, 26, 12, 34, 56),
                UtcStamp.parse("2026-08-26T12:34:56+00:00"));
        assertNull(UtcStamp.parse(""));
    }

    @Test
    @Order(2)
    void personaCrudAndUniqueName() {
        List<PersonaContract> all = personas.listAll();
        assertEquals(BuiltinPersonas.all().size(), all.size(), "内置人设应已播种");
        assertTrue(all.stream().allMatch(p -> p.getId() != null), "id 必须来自数据库");

        PersonaContract custom = new PersonaContract();
        custom.setName(personas.uniqueName("大厂技术面试官"));
        assertEquals("大厂技术面试官 副本", custom.getName(), "重名要自动加副本后缀");
        custom.setJobTitle("测试岗");
        PersonaContract saved = personas.save(custom);
        assertNotNull(saved.getId());

        // 存进去再读出来，17 维契约不能丢
        PersonaContract loaded = personas.listAll().stream()
                .filter(p -> p.getId().equals(saved.getId())).findFirst().orElseThrow();
        assertEquals("测试岗", loaded.getJobTitle());
        assertFalse(loaded.isBuiltin());

        personas.delete(saved.getId());
        assertEquals(all.size(), personas.listAll().size());
    }

    @Test
    @Order(3)
    void sessionStateAndRecordUpsert() {
        PersonaContract persona = personas.listAll().get(0);
        ResumeProfile profile = new ResumeProfile();
        profile.setCandidateName("张三");
        profile.setRawText("简历原文");
        StoredResume resume = library.saveResume(profile, "C:/tmp/a.pdf");
        assertEquals("张三", resume.label());

        int sessionId = sessions.create("测试面试", persona, 30, resume.id(), null);
        assertEquals(SessionStatus.DRAFT, sessions.status(sessionId));
        assertNull(sessions.loadState(sessionId), "还没写过 state 时应当是 null");

        sessions.setStatus(sessionId, SessionStatus.RUNNING);
        assertEquals(SessionStatus.RUNNING, sessions.status(sessionId));

        InterviewState state = new InterviewState();
        state.setSessionId(sessionId);
        state.setPersona(persona);
        state.setPlan(Plans.build(persona, 30, false, null));
        state.setElapsedMs(120_000);
        state.enterPhase(InterviewPhase.TECH_DEPTH);
        state.openQuestion(TurnIntent.ASK_NEW, "问 Redis 过期策略", "Redis 过期策略",
                "缓存", 2, 7, false);
        TurnRecord turn = state.appendTurn(Speaker.CANDIDATE, "我们用的是惰性删除",
                1000, 4000, null, false);

        sessions.persistState(state);
        sessions.upsertTurn(sessionId, turn);
        sessions.upsertQuestion(sessionId, state.currentQuestion());

        InterviewState back = sessions.loadState(sessionId);
        assertNotNull(back, "state 应当能读回来");
        assertEquals(InterviewPhase.TECH_DEPTH, back.getPhase());
        assertEquals(1, back.getQuestions().size());
        assertEquals("Redis 过期策略", back.getQuestions().get(0).getTargetSkill());
        assertEquals(2, back.getQuestions().get(0).getDepth());

        // 同一轮写第二次要覆盖而不是撞唯一约束
        turn.setText("我们用的是惰性删除加定期删除");
        turn.setWasInterrupted(true);
        sessions.upsertTurn(sessionId, turn);
        List<TurnRecord> turns = sessions.loadTurns(sessionId);
        assertEquals(1, turns.size(), "upsert 不该插出第二行");
        assertEquals("我们用的是惰性删除加定期删除", turns.get(0).getText());
        assertTrue(turns.get(0).isWasInterrupted());
        assertEquals(Speaker.CANDIDATE, turns.get(0).getSpeaker(), "speaker 不该被覆盖");

        // 收尾整体对齐，走的是同一条 upsert
        sessions.syncRecords(state);
        assertEquals(1, sessions.loadTurns(sessionId).size());
        assertEquals(1, sessions.loadQuestions(sessionId).size());

        sessions.finish(sessionId, 120_000, "");
        sessions.setStatus(sessionId, SessionStatus.REVIEWING);

        List<SessionSummary> recent = sessions.listRecent(10);
        assertTrue(recent.stream().anyMatch(s -> s.id() == sessionId));
        SessionSummary summary = recent.stream()
                .filter(s -> s.id() == sessionId).findFirst().orElseThrow();
        assertEquals(120_000, summary.durationMs());
        assertEquals(persona.getName(), summary.personaName());
        assertNotNull(summary.createdAt(), "时间戳要能读回来");
    }

    @Test
    @Order(4)
    void reviewMistakeMergeAndTrends() {
        PersonaContract persona = personas.listAll().get(0);
        int sessionId = sessions.create("复盘测试", persona, 30, null, null);

        ReviewReport report = new ReviewReport();
        report.setSessionId(sessionId);
        report.setOverallScore(72.5);
        report.setHeadline("勉强通过");
        report.setDimensions(List.of(
                new DimensionScore(ScoreDimension.TECH_DEPTH, 68.0, "深度不足", List.of("原句")),
                new DimensionScore(ScoreDimension.EXPRESSION, 80.0, "表达清晰", List.of())));
        report.setMistakes(List.of(new MistakeItem("Redis 分布式锁的续期机制", "分布式",
                "锁到期业务没执行完怎么办", "没答上来", List.of("看门狗", "Redlock"),
                GapSeverity.MAJOR, "看 Redisson 文档")));
        reviews.saveReview(report);

        ReviewReport loaded = reviews.getReview(sessionId);
        assertNotNull(loaded);
        assertEquals(72.5, loaded.getOverallScore(), 1e-9);
        assertEquals(2, loaded.getDimensions().size());
        // 总分要回写到会话行，列表页才看得到
        assertEquals(72.5, sessions.listRecent(50).stream()
                .filter(s -> s.id() == sessionId).findFirst().orElseThrow().overallScore(), 1e-9);

        List<StoredMistake> pending = reviews.listMistakes(false, "", 50);
        StoredMistake first = pending.stream()
                .filter(m -> m.item().knowledgePoint().equals("Redis 分布式锁的续期机制"))
                .findFirst().orElseThrow();
        assertEquals(1, first.hitCount());
        assertEquals(List.of("看门狗", "Redlock"), first.item().keyPoints());
        assertEquals(GapSeverity.MAJOR, first.item().severity());

        // 第二场再答错同一个知识点：合并累加，严重度升到 blocker
        int second = sessions.create("复盘测试二", persona, 30, null, null);
        ReviewReport again = new ReviewReport();
        again.setSessionId(second);
        again.setOverallScore(60.0);
        again.setMistakes(List.of(new MistakeItem("Redis 分布式锁的续期机制", "分布式",
                "再问一次", "还是不会", List.of("看门狗"),
                GapSeverity.BLOCKER, "")));
        reviews.saveReview(again);

        StoredMistake merged = reviews.listMistakes(false, "", 50).stream()
                .filter(m -> m.item().knowledgePoint().equals("Redis 分布式锁的续期机制"))
                .findFirst().orElseThrow();
        assertEquals(2, merged.hitCount(), "同一知识点应合并累加");
        assertEquals(GapSeverity.BLOCKER, merged.item().severity(), "严重度只升不降");
        assertEquals("看 Redisson 文档", merged.item().reviewHint(), "新值为空时保留旧值");
        assertEquals(second, merged.sessionId(), "session 记最后一次命中");

        assertTrue(reviews.topics().contains("分布式"));
        int[] counts = reviews.mistakeCounts();
        assertTrue(counts[0] >= 1);

        reviews.setMastered(merged.id(), true);
        assertTrue(reviews.listMistakes(false, "", 50).stream()
                .noneMatch(m -> m.id() == merged.id()), "已掌握的默认不出现");
        assertTrue(reviews.listMistakes(true, "", 50).stream()
                .anyMatch(m -> m.id() == merged.id()));

        Map<ScoreDimension, List<TrendPoint>> series = reviews.dimensionSeries(20);
        assertTrue(series.containsKey(ScoreDimension.TECH_DEPTH));
        assertEquals(68.0, series.get(ScoreDimension.TECH_DEPTH).get(0).score(), 1e-9);

        List<TrendPoint> overall = reviews.overallSeries(20);
        assertTrue(overall.size() >= 2);
        // 序列必须是时间正序，画线才不会反着走
        assertTrue(overall.get(overall.size() - 1).score() <= 72.5);

        GlobalStats stats = sessions.stats();
        assertTrue(stats.totalSessions() >= 3);
        assertNotNull(stats.averageScore());
        assertNotNull(stats.bestScore());
        assertNotNull(stats.latestScore());
    }
}
