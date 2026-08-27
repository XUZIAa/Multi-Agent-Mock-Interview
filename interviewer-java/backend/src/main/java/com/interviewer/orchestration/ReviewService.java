package com.interviewer.orchestration;

import com.interviewer.agents.Reviewer;
import com.interviewer.analysis.Prosody;
import com.interviewer.analysis.Transcript;
import com.interviewer.core.Text;
import com.interviewer.core.event.AppEvent;
import com.interviewer.core.event.EventBus;
import com.interviewer.core.type.SessionStatus;
import com.interviewer.data.repository.ReviewRepository;
import com.interviewer.data.repository.SessionRepository;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.review.ProsodyReport;
import com.interviewer.domain.review.ReviewReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 面试后的离线复盘。客观指标先算好，模型只负责解读。 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final EventBus bus;
    private final Reviewer reviewer;
    private final SessionRepository sessions;
    private final ReviewRepository reviews;

    public ReviewService(EventBus bus, Reviewer reviewer, SessionRepository sessions,
                         ReviewRepository reviews) {
        this.bus = bus;
        this.reviewer = reviewer;
        this.sessions = sessions;
        this.reviews = reviews;
    }

    public ReviewReport generate(InterviewState state) {
        emit("prosody", 5, "正在分析语速与停顿");
        ProsodyReport prosody = Prosody.analyze(state.getTurns(), state.getElapsedMs());
        bus.emit(new AppEvent.ProsodySnapshot(prosody.wordsPerMinute(), prosody.fillerRatio(),
                prosody.pauseRatio(), prosody.longestPauseMs()));

        emit("transcript", 12, "正在整理逐字稿");
        Reviewer.Material material = new Reviewer.Material(
                Transcript.formatTurns(state.getTurns()),
                Transcript.formatQuestions(state.getQuestions()),
                Transcript.codingSummary(state),
                prosody,
                Prosody.summaryForModel(prosody));

        ReviewReport report = reviewer.compose(state, material, this::emit);

        emit("persist", 96, "正在保存复盘结果");
        reviews.saveReview(report);
        sessions.setStatus(state.getSessionId(), SessionStatus.COMPLETED);
        emit("done", 100, "复盘已生成");
        log.info("复盘完成 session={} 总分={} 错题={} 专项={}",
                state.getSessionId(), Text.fixed(report.getOverallScore(), 1),
                report.getMistakes().size(), report.getImprovementPlans().size());
        return report;
    }

    public ReviewReport load(int sessionId) {
        return reviews.getReview(sessionId);
    }

    private void emit(String stage, int percent, String detail) {
        bus.emit(new AppEvent.ReviewProgress(stage, percent, Text.safe(detail)));
    }
}
