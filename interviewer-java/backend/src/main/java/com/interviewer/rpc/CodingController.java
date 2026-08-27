package com.interviewer.rpc;

import com.interviewer.agents.CodingComposer;
import com.interviewer.analysis.CodeRunner;
import com.interviewer.domain.coding.Coding;
import com.interviewer.domain.coding.CodingChallenge;
import com.interviewer.domain.coding.JudgeOutcome;
import com.interviewer.domain.coding.RunOutcome;
import com.interviewer.domain.company.Companies;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.orchestration.InterviewEngine;
import com.interviewer.rpc.dto.ComposeChallengeBody;
import com.interviewer.rpc.dto.JudgeCodeBody;
import com.interviewer.rpc.dto.RunCodeBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "coding")
public class CodingController {

    private final InterviewEngine engine;
    private final CodingComposer composer;
    private final CodeRunner runner;

    public CodingController(InterviewEngine engine, CodingComposer composer, CodeRunner runner) {
        this.engine = engine;
        this.composer = composer;
        this.runner = runner;
    }

    @PostMapping("/coding/challenge")
    public CodingChallenge challenge(@Valid @RequestBody ComposeChallengeBody body) {
        InterviewState state = engine.state();
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有正在进行的面试，无法出编码题");
        }
        return composer.compose(body.skill(), state.getPersona().getJobTitle(),
                Companies.levelExpectation(state.getCompanyTier(), state.getJobLevel()),
                (int) (state.getPlan().totalMs() / 60_000));
    }

    @PostMapping("/coding/run")
    public RunOutcome run(@Valid @RequestBody RunCodeBody body) {
        requireLanguage(body.language());
        if (body.source().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代码是空的");
        }
        return runner.run(body.language(), body.source(), body.stdin());
    }

    @PostMapping("/coding/judge")
    public JudgeOutcome judge(@Valid @RequestBody JudgeCodeBody body) {
        requireLanguage(body.language());
        if (body.cases().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这道题没有用例可跑");
        }
        return runner.judge(body.language(), body.source(), body.cases());
    }

    private static void requireLanguage(String language) {
        if (!Coding.supported(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持运行 " + language);
        }
    }
}
