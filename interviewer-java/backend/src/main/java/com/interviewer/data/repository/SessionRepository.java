package com.interviewer.data.repository;

import com.interviewer.core.Text;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.SessionStatus;
import com.interviewer.core.type.Speaker;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.data.UtcStamp;
import com.interviewer.data.dto.GlobalStats;
import com.interviewer.data.dto.SessionSummary;
import com.interviewer.data.entity.QuestionRow;
import com.interviewer.data.entity.SessionRow;
import com.interviewer.data.entity.TurnRow;
import com.interviewer.data.mapper.QuestionMapper;
import com.interviewer.data.mapper.SessionMapper;
import com.interviewer.data.mapper.TurnMapper;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.QuestionRecord;
import com.interviewer.domain.interview.TurnRecord;
import com.interviewer.domain.persona.PersonaContract;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 会话仓储。面试进行中每轮都会写，收尾时整体对齐一次。 */
@Repository
public class SessionRepository {

    /** 批量写入的切片大小。SQLite 单写者，一次事务塞太多会长时间持锁。 */
    private static final int CHUNK = 80;

    private final SessionMapper sessions;
    private final TurnMapper turns;
    private final QuestionMapper questions;
    private final JsonCodec codec;

    public SessionRepository(SessionMapper sessions, TurnMapper turns, QuestionMapper questions,
                             JsonCodec codec) {
        this.sessions = sessions;
        this.turns = turns;
        this.questions = questions;
        this.codec = codec;
    }

    public int create(String title, PersonaContract persona, int plannedMinutes,
                      Integer resumeId, Integer jobId) {
        LocalDateTime now = UtcStamp.now();
        SessionRow row = new SessionRow();
        row.setTitle(Text.safe(title));
        row.setStatus(SessionStatus.DRAFT.value());
        row.setPersonaId(persona.getId());
        row.setPersonaName(persona.getName());
        row.setResumeId(resumeId);
        row.setJobId(jobId);
        row.setPlannedMinutes(plannedMinutes);
        row.setDurationMs(0);
        row.setAudioPath("");
        row.setState("{}");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        sessions.insert(row);
        return row.getId();
    }

    /** 进入进行中打开始时间，完成或中止打结束时间。 */
    public void setStatus(int sessionId, SessionStatus status) {
        LocalDateTime now = UtcStamp.now();
        if (status == SessionStatus.RUNNING) {
            sessions.markStarted(sessionId, status.value(), now);
            return;
        }
        if (status == SessionStatus.COMPLETED || status == SessionStatus.ABORTED) {
            sessions.markEnded(sessionId, status.value(), now);
            return;
        }
        sessions.updateStatus(sessionId, status.value(), now);
    }

    public SessionStatus status(int sessionId) {
        String raw = sessions.statusOf(sessionId);
        return raw == null ? null : SessionStatus.of(raw);
    }

    /** 每轮落一次。整场崩了之后靠这份 state 恢复。 */
    public void persistState(InterviewState state) {
        sessions.updateState(state.getSessionId(), codec.write(state),
                (int) state.getElapsedMs(), UtcStamp.now());
    }

    public InterviewState loadState(int sessionId) {
        String raw = sessions.stateOf(sessionId);
        if (raw == null || raw.isBlank() || "{}".equals(raw.strip())) {
            return null;
        }
        return codec.read(raw, InterviewState.class);
    }

    public void upsertTurn(int sessionId, TurnRecord turn) {
        turns.upsert(toRow(sessionId, turn));
    }

    public void upsertQuestion(int sessionId, QuestionRecord question) {
        questions.upsert(toRow(sessionId, question));
    }

    /**
     * 收尾时整体对齐一次，按批提交。
     *
     * <p>必须走自然键 upsert：这些行在面试过程中已逐条写过，直接插新行会撞
     * (session_id, question_index) 唯一约束。
     */
    public void syncRecords(InterviewState state) {
        for (int start = 0; start < state.getQuestions().size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, state.getQuestions().size());
            flushQuestions(state.getSessionId(), state.getQuestions().subList(start, end));
        }
        for (int start = 0; start < state.getTurns().size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, state.getTurns().size());
            flushTurns(state.getSessionId(), state.getTurns().subList(start, end));
        }
    }

    @Transactional
    public void flushQuestions(int sessionId, List<QuestionRecord> batch) {
        batch.forEach(q -> questions.upsert(toRow(sessionId, q)));
    }

    @Transactional
    public void flushTurns(int sessionId, List<TurnRecord> batch) {
        batch.forEach(t -> turns.upsert(toRow(sessionId, t)));
    }

    public void finish(int sessionId, int durationMs, String audioPath) {
        sessions.finish(sessionId, durationMs, Text.safe(audioPath), UtcStamp.now());
    }

    public void setScore(int sessionId, double overall) {
        sessions.updateScore(sessionId, overall, UtcStamp.now());
    }

    public List<TurnRecord> loadTurns(int sessionId) {
        List<TurnRecord> out = new ArrayList<>();
        for (TurnRow row : turns.listBySession(sessionId)) {
            TurnRecord turn = new TurnRecord();
            turn.setIndex(row.getTurnIndex());
            turn.setSpeaker(Speaker.of(row.getSpeaker()));
            turn.setText(Text.safe(row.getText()));
            turn.setStartedAtMs(row.getStartedAtMs() == null ? 0 : row.getStartedAtMs());
            turn.setDurationMs(row.getDurationMs() == null ? 0 : row.getDurationMs());
            turn.setWasInterrupted(Boolean.TRUE.equals(row.getWasInterrupted()));
            turn.setQuestionIndex(row.getQuestionIndex());
            out.add(turn);
        }
        return out;
    }

    public List<QuestionRecord> loadQuestions(int sessionId) {
        List<QuestionRecord> out = new ArrayList<>();
        for (QuestionRow row : questions.listBySession(sessionId)) {
            QuestionRecord q = new QuestionRecord();
            q.setIndex(row.getQuestionIndex());
            q.setPhase(InterviewPhase.of(row.getPhase()));
            q.setIntent(Text.notBlank(row.getIntent())
                    ? TurnIntent.of(row.getIntent()) : TurnIntent.ASK_NEW);
            q.setBrief(row.getBrief());
            q.setTargetSkill(row.getTargetSkill());
            q.setSpokenText(row.getSpokenText());
            q.setAnswerText(row.getAnswerText());
            q.setAskedAtMs(row.getAskedAtMs() == null ? 0 : row.getAskedAtMs());
            q.setFollowUpDepth(row.getFollowUpDepth() == null ? 0 : row.getFollowUpDepth());
            q.setQuality(row.getQuality());
            out.add(q);
        }
        return out;
    }

    public List<SessionSummary> listRecent(int limit) {
        List<SessionSummary> out = new ArrayList<>();
        for (SessionRow row : sessions.listRecent(limit)) {
            out.add(new SessionSummary(row.getId(), Text.safe(row.getTitle()),
                    SessionStatus.of(row.getStatus()), Text.safe(row.getPersonaName()),
                    row.getCreatedAt(), row.getDurationMs() == null ? 0 : row.getDurationMs(),
                    row.getOverallScore(),
                    row.getPlannedMinutes() == null ? 0 : row.getPlannedMinutes()));
        }
        return out;
    }

    public List<SessionRow> listByStatus(SessionStatus status) {
        return sessions.listByStatus(status.value());
    }

    public GlobalStats stats() {
        Map<String, Object> raw = sessions.stats();
        long totalMs = asLong(raw.get("total_ms"));
        return new GlobalStats(
                (int) asLong(raw.get("total_sessions")),
                (int) asLong(raw.get("completed_sessions")),
                (int) (totalMs / 60_000),
                asDouble(raw.get("best_score")),
                asDouble(raw.get("latest_score")),
                asDouble(raw.get("average_score")));
    }

    public void delete(int sessionId) {
        sessions.deleteById(sessionId);
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** 一场都没复盘过时聚合结果是 null，不能给 0——界面会显示「平均 0 分」。 */
    private static Double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static TurnRow toRow(int sessionId, TurnRecord turn) {
        TurnRow row = new TurnRow();
        row.setSessionId(sessionId);
        row.setTurnIndex(turn.getIndex());
        row.setSpeaker(turn.getSpeaker() == null ? "" : turn.getSpeaker().value());
        row.setText(Text.safe(turn.getText()));
        row.setStartedAtMs((int) turn.getStartedAtMs());
        row.setDurationMs((int) turn.getDurationMs());
        row.setIntent(turn.getIntent() == null ? "" : turn.getIntent().value());
        row.setWasInterrupted(turn.isWasInterrupted());
        row.setQuestionIndex(turn.getQuestionIndex());
        return row;
    }

    private static QuestionRow toRow(int sessionId, QuestionRecord question) {
        QuestionRow row = new QuestionRow();
        row.setSessionId(sessionId);
        row.setQuestionIndex(question.getIndex());
        row.setPhase(question.getPhase() == null ? "" : question.getPhase().value());
        row.setIntent(question.getIntent() == null ? "" : question.getIntent().value());
        row.setBrief(Text.safe(question.getBrief()));
        row.setTargetSkill(Text.safe(question.getTargetSkill()));
        row.setSpokenText(Text.safe(question.getSpokenText()));
        row.setAnswerText(Text.safe(question.getAnswerText()));
        row.setAskedAtMs((int) question.getAskedAtMs());
        row.setFollowUpDepth(question.getFollowUpDepth());
        row.setQuality(question.getQuality());
        return row;
    }
}
