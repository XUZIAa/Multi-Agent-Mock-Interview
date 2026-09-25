package com.interviewer.domain.interview;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.bank.DepthAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 一道问出去的题。答案会随候选人多段发言累加，质量与推进动作事后回填。 */
@Getter
@Setter
public class QuestionRecord {

    private int index;
    private InterviewPhase phase;
    private TurnIntent intent;
    private String brief = "";
    private String targetSkill = "";
    private String domain = "";
    private int depth = 1;
    /** 临场生成的追问不出自题库。 */
    @Schema(nullable = true)
    private Integer bankQuestionId;
    private String spokenText = "";
    private long askedAtMs;
    private int followUpDepth;
    private String answerText = "";
    /** 还没评过分时是空的。「没答」和「答得差」不能混成同一个 0。 */
    @Schema(nullable = true)
    private Double quality;
    @Schema(nullable = true)
    private DepthAction depthAction;

    /** 进度摘要里的一行。取实际问出口的话，没有就退回导演的 brief。 */
    @JsonIgnore
    public String shortText() {
        String text = spokenText.isEmpty() ? brief : spokenText;
        String head = text.length() > 70 ? text.substring(0, 70) : text;
        return head.replace("\n", " ");
    }

    @JsonIgnore
    public boolean hasAnswer() {
        return !answerText.isBlank();
    }

    public void setBrief(String value) {
        this.brief = value == null ? "" : value;
    }

    public void setTargetSkill(String value) {
        this.targetSkill = value == null ? "" : value;
    }

    public void setDomain(String value) {
        this.domain = value == null ? "" : value;
    }

    public void setSpokenText(String value) {
        this.spokenText = value == null ? "" : value;
    }

    public void setAnswerText(String value) {
        this.answerText = value == null ? "" : value;
    }

    /** 候选人的一段发言并进答案。多段之间用空格接，与 Python 版一致。 */
    public void appendAnswer(String piece) {
        if (piece == null || piece.isBlank()) {
            return;
        }
        this.answerText = (answerText + " " + piece).strip();
    }
}
