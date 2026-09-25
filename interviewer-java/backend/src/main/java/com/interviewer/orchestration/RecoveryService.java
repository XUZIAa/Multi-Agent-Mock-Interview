package com.interviewer.orchestration;

import com.interviewer.core.Text;
import com.interviewer.core.type.SessionStatus;
import com.interviewer.data.dto.SessionSummary;
import com.interviewer.data.repository.SessionRepository;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.QuestionRecord;
import com.interviewer.domain.interview.TurnRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 崩溃或强退后的兜底。每轮都落了盘，所以记录不会丢，只需要认领。 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final SessionRepository sessions;

    public RecoveryService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    /** 一场中断的面试。 */
    public record InterruptedSession(int sessionId, String title, long durationMs,
                                     boolean reviewable) {
    }

    /**
     * 扫出状态还停在进行中的场次并认领。
     *
     * <p>认领即改成待复盘：不改的话下次启动还会再报一次，而用户已经看过了。
     */
    public List<InterruptedSession> scan() {
        List<SessionSummary> recent = sessions.listRecent(40);
        List<InterruptedSession> out = new ArrayList<>();
        for (SessionSummary summary : recent) {
            if (summary.status() != SessionStatus.RUNNING) {
                continue;
            }
            InterviewState state = sessions.loadState(summary.id());
            long duration = state != null ? state.getElapsedMs() : summary.durationMs();
            out.add(new InterruptedSession(summary.id(), summary.title(), duration,
                    state != null && state.isReviewable()));
            sessions.setStatus(summary.id(), SessionStatus.REVIEWING);
            log.info("发现中断的面试 session={} 时长={}ms", summary.id(), duration);
        }
        return out;
    }

    /**
     * 载入并补齐状态。
     *
     * <p>逐轮落盘比整体状态落盘更频繁（每次转写就写一行，而 state 是每轮末尾写一次），
     * 所以崩溃时表里的尾部数据可能比 state 里的新，要拿表数据补上。
     */
    public InterviewState loadState(int sessionId) {
        InterviewState state = sessions.loadState(sessionId);
        if (state == null) {
            return null;
        }

        List<TurnRecord> turns = sessions.loadTurns(sessionId);
        if (turns.size() >= state.getTurns().size()) {
            state.setTurns(new ArrayList<>(turns));
            int maxIndex = turns.stream().mapToInt(TurnRecord::getIndex).max()
                    .orElse(state.getTurnIndex());
            state.setTurnIndex(Math.max(maxIndex, state.getTurnIndex()));
        }

        List<QuestionRecord> questions = sessions.loadQuestions(sessionId);
        if (questions.size() > state.getQuestions().size()) {
            Map<Integer, QuestionRecord> merged = new LinkedHashMap<>();
            state.getQuestions().forEach(q -> merged.put(q.getIndex(), q));
            for (QuestionRecord question : questions) {
                QuestionRecord existing = merged.get(question.getIndex());
                if (existing == null) {
                    merged.put(question.getIndex(), question);
                    continue;
                }
                // state 里那份带着完整的推进信息，只补它缺的文本
                if (!Text.notBlank(existing.getSpokenText())) {
                    existing.setSpokenText(question.getSpokenText());
                }
                if (!Text.notBlank(existing.getAnswerText())) {
                    existing.setAnswerText(question.getAnswerText());
                }
            }
            state.setQuestions(new ArrayList<>(new TreeMap<>(merged).values()));
        }
        return state;
    }
}
