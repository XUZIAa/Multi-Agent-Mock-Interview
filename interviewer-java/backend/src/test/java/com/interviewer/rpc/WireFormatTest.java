package com.interviewer.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.event.AppEvent;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.core.type.StarElement;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.rpc.dto.BuildSessionBody;
import com.interviewer.rpc.dto.ComposeChallengeBody;
import com.interviewer.rpc.dto.StopResult;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 线上格式。
 *
 * <p>前端按字段名取值，一个键名错了就是运行时的 undefined，编译期拦不住。枚举做 Map 键
 * 时 Jackson 默认走的是常量名（{@code TECH_DEPTH}），必须确认走的是线上值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WireFormatTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void enumMapKeysUseTheWireValue() throws Exception {
        Map<ScoreDimension, Double> scores = new EnumMap<>(ScoreDimension.class);
        scores.put(ScoreDimension.TECH_DEPTH, 68.0);
        scores.put(ScoreDimension.VALUE_FIT, 71.5);
        String json = mapper.writeValueAsString(new AppEvent.LiveScoreUpdated(scores));
        assertEquals("{\"scores\":{\"tech_depth\":68.0,\"value_fit\":71.5}}", json);
    }

    @Test
    void eventPayloadsUseSnakeCase() throws Exception {
        assertEquals("{\"turn_id\":3,\"speaker\":\"candidate\",\"text\":\"嗯\","
                        + "\"started_at_ms\":100,\"duration_ms\":900}",
                mapper.writeValueAsString(
                        new AppEvent.TranscriptCommitted(3, "candidate", "嗯", 100, 900)));
        assertEquals("{\"user_message\":\"出错了\",\"detail\":\"\",\"fatal\":false}",
                mapper.writeValueAsString(new AppEvent.EngineFailure("出错了")));
        assertEquals("{\"task_id\":\"t1\",\"stage\":\"读取\",\"percent\":10}",
                mapper.writeValueAsString(new AppEvent.TaskProgress("t1", "读取", 10)));
        // 布尔字段的 is 前缀不能被剥掉
        assertTrue(mapper.writeValueAsString(new AppEvent.StarProgress(
                Set.of(StarElement.SITUATION), Set.of(), true)).contains("\"is_behavioral\":true"));
    }

    @Test
    void eventNamesFollowTheClassName() {
        assertEquals("audio_level", AppEvent.nameOf(AppEvent.AudioLevel.class));
        assertEquals("realtime_state_changed",
                AppEvent.nameOf(AppEvent.RealtimeStateChanged.class));
        assertEquals("task_progress", AppEvent.nameOf(AppEvent.TaskProgress.class));
        assertEquals(EventHub.EVENT_NAMES.size(),
                AppEvent.class.getPermittedSubclasses().length);
    }

    @Test
    void enumValuesSerializeAsWireValues() throws Exception {
        assertEquals("\"tech_depth\"", mapper.writeValueAsString(InterviewPhase.TECH_DEPTH));
        assertEquals("\"ask_new\"", mapper.writeValueAsString(TurnIntent.ASK_NEW));
        String json = mapper.writeValueAsString(
                new AppEvent.PhaseChanged(InterviewPhase.CANDIDATE_QA, "轮到你问"));
        assertEquals("{\"phase\":\"candidate_qa\",\"reason\":\"轮到你问\"}", json);
    }

    @Test
    void nullSessionIdStaysExplicit() throws Exception {
        // 前端按 session_id === null 判断有没有可收尾的会话，字段不能被省略
        assertEquals("{\"session_id\":null,\"reviewable\":false,\"elapsed_ms\":0}",
                mapper.writeValueAsString(StopResult.NONE));
    }

    @Test
    void requestBodiesAcceptSnakeCase() throws Exception {
        String json = """
                {"task_id":"t9","persona":{"name":"张面试官"},"resume_id":3,"job_id":null,
                 "tier":"big_tech","level":"senior","minutes":45,"coding_enabled":true}
                """;
        BuildSessionBody body = mapper.readValue(json, BuildSessionBody.class);
        assertEquals("t9", body.taskId());
        assertEquals(3, body.resumeId());
        assertNull(body.jobId());
        assertEquals(45, body.minutes());
        assertTrue(body.codingEnabled());
        assertEquals("张面试官", body.persona().getName());

        // 缺字段的请求体不能炸出 null：下游直接拿去拼提示词
        assertEquals("", mapper.readValue("{}", ComposeChallengeBody.class).skill());
    }
}
