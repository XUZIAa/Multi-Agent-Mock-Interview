package com.interviewer.domain.interview;

import com.interviewer.core.type.Speaker;
import com.interviewer.core.type.TurnIntent;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 一次发言。index 从 1 起，是逐字稿批注的锚点，不能改起点。 */
@Getter
@Setter
public class TurnRecord {

    private int index;
    private Speaker speaker;
    private String text = "";
    private long startedAtMs;
    private long durationMs;
    /** 候选人的发言没有意图；面试官的才有。 */
    @Schema(nullable = true)
    private TurnIntent intent;
    private boolean wasInterrupted;
    /** 开场寒暄这类不挂在任何题目下。 */
    @Schema(nullable = true)
    private Integer questionIndex;

    public TurnRecord() {
    }

    public TurnRecord(int index, Speaker speaker, String text, long startedAtMs, long durationMs,
                      TurnIntent intent, boolean wasInterrupted, Integer questionIndex) {
        this.index = index;
        this.speaker = speaker;
        this.text = text == null ? "" : text;
        this.startedAtMs = startedAtMs;
        this.durationMs = durationMs;
        this.intent = intent;
        this.wasInterrupted = wasInterrupted;
        this.questionIndex = questionIndex;
    }
}
