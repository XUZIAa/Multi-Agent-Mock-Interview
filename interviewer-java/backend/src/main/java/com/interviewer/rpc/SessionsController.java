package com.interviewer.rpc;

import com.interviewer.core.type.SessionStatus;
import com.interviewer.data.dto.GlobalStats;
import com.interviewer.data.dto.SessionSummary;
import com.interviewer.data.repository.SessionRepository;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.TurnRecord;
import com.interviewer.orchestration.RecoveryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "sessions")
public class SessionsController {

    private final SessionRepository sessions;
    private final RecoveryService recovery;

    public SessionsController(SessionRepository sessions, RecoveryService recovery) {
        this.sessions = sessions;
        this.recovery = recovery;
    }

    @GetMapping("/sessions")
    public List<SessionSummary> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return sessions.listRecent(limit);
    }

    @GetMapping("/sessions/stats")
    public GlobalStats stats() {
        return sessions.stats();
    }

    @GetMapping("/sessions/{session_id}/state")
    public InterviewState state(@PathVariable("session_id") int sessionId) {
        return recovery.loadState(sessionId);
    }

    @GetMapping("/sessions/{session_id}/turns")
    public List<TurnRecord> turns(@PathVariable("session_id") int sessionId) {
        return sessions.loadTurns(sessionId);
    }

    @GetMapping("/sessions/{session_id}/status")
    public SessionStatus status(@PathVariable("session_id") int sessionId) {
        return sessions.status(sessionId);
    }
}
