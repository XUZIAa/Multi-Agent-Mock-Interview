package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.type.GapSeverity;
import java.util.List;

/** 一条错题。同一知识点反复答错时按 knowledgePoint 合并并累计次数。 */
public record MistakeItem(String knowledgePoint, String topic, String question,
                          String candidateAnswer, List<String> keyPoints,
                          GapSeverity severity, String reviewHint) {

    @JsonCreator
    public MistakeItem(@JsonProperty("knowledge_point") String knowledgePoint,
                       @JsonProperty("topic") String topic,
                       @JsonProperty("question") String question,
                       @JsonProperty("candidate_answer") String candidateAnswer,
                       @JsonProperty("key_points") List<String> keyPoints,
                       @JsonProperty("severity") GapSeverity severity,
                       @JsonProperty("review_hint") String reviewHint) {
        this.knowledgePoint = Text.safe(knowledgePoint);
        this.topic = Text.safe(topic);
        this.question = Text.safe(question);
        this.candidateAnswer = Text.safe(candidateAnswer);
        this.keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        this.severity = severity == null ? GapSeverity.MAJOR : severity;
        this.reviewHint = Text.safe(reviewHint);
    }
}
