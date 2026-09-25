package com.interviewer.rpc;

import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.review.ReviewReport;
import com.interviewer.orchestration.RecoveryService;
import com.interviewer.orchestration.ReviewService;
import com.interviewer.rpc.dto.GenerateReviewBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "review")
public class ReviewController {

    private final ReviewService review;
    private final RecoveryService recovery;

    public ReviewController(ReviewService review, RecoveryService recovery) {
        this.review = review;
        this.recovery = recovery;
    }

    @PostMapping("/review/generate")
    public ReviewReport generate(@Valid @RequestBody GenerateReviewBody body) {
        InterviewState state = recovery.loadState(body.sessionId());
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "找不到会话 " + body.sessionId());
        }
        return review.generate(state);
    }

    // 路径参数沿用 snake_case，与前端生成的类型契约逐字对齐
    @GetMapping("/review/{session_id}")
    public ReviewReport load(@PathVariable("session_id") int sessionId) {
        return review.load(sessionId);
    }
}
