package com.interviewer.rpc;

import com.interviewer.domain.interview.InterviewState;
import com.interviewer.orchestration.InterviewEngine;
import com.interviewer.orchestration.RecoveryService;
import com.interviewer.rpc.dto.HintBody;
import com.interviewer.rpc.dto.MuteBody;
import com.interviewer.rpc.dto.Ok;
import com.interviewer.rpc.dto.StartInterviewBody;
import com.interviewer.rpc.dto.StopInterviewBody;
import com.interviewer.rpc.dto.StopResult;
import com.interviewer.rpc.dto.SubmitCodeBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "engine")
public class EngineController {

    private final InterviewEngine engine;
    private final RecoveryService recovery;

    public EngineController(InterviewEngine engine, RecoveryService recovery) {
        this.engine = engine;
        this.recovery = recovery;
    }

    @PostMapping("/engine/start")
    public Ok start(@Valid @RequestBody StartInterviewBody body) {
        InterviewState state = recovery.loadState(body.sessionId());
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "找不到会话 " + body.sessionId());
        }
        engine.start(state);
        return Ok.DONE;
    }

    @PostMapping("/engine/stop")
    public StopResult stop(@Valid @RequestBody StopInterviewBody body) {
        InterviewState state = engine.stop(body.aborted());
        if (state == null) {
            return StopResult.NONE;
        }
        return new StopResult(state.getSessionId(), state.isReviewable(), state.getElapsedMs());
    }

    /**
     * 面试从开始到结束都挂在这个请求上，正常要等几十分钟。
     *
     * <p>刻意用阻塞而非 Spring 的异步返回：异步一旦启用就受容器的 asyncTimeout 约束（Tomcat
     * 默认 30 秒），而这里必须能等满整场。占住一个请求线程的代价在单用户本机场景可以忽略。
     */
    @PostMapping("/engine/wait-finished")
    public Ok waitFinished() {
        try {
            engine.waitFinished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Ok.DONE;
    }

    @PostMapping("/engine/mute")
    public Ok mute(@Valid @RequestBody MuteBody body) {
        engine.setMuted(body.muted());
        return Ok.DONE;
    }

    @PostMapping("/engine/hint")
    public Ok hint(@Valid @RequestBody HintBody body) {
        engine.requestHint(body.auto());
        return Ok.DONE;
    }

    @PostMapping("/engine/interrupt")
    public Ok interrupt() {
        engine.interruptInterviewer();
        return Ok.DONE;
    }

    @PostMapping("/engine/code")
    public Ok submitCode(@Valid @RequestBody SubmitCodeBody body) {
        engine.submitCode(body.language(), body.source());
        return Ok.DONE;
    }

    @PostMapping("/engine/finish-early")
    public Ok finishEarly() {
        engine.finishEarly();
        return Ok.DONE;
    }
}
