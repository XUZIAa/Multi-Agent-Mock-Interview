package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.type.AnnotationKind;

/**
 * 逐字稿上的高亮批注，锚定到具体轮次与原文片段。
 *
 * <p>turnIndex 与 TurnRecord.index 同源，从 1 起。前端拿它直接查原文，
 * 展示时不要再加一。
 */
public record TranscriptAnnotation(int turnIndex, AnnotationKind kind, String quote,
                                   String comment) {

    @JsonCreator
    public TranscriptAnnotation(@JsonProperty("turn_index") int turnIndex,
                                @JsonProperty("kind") AnnotationKind kind,
                                @JsonProperty("quote") String quote,
                                @JsonProperty("comment") String comment) {
        this.turnIndex = turnIndex;
        this.kind = kind;
        this.quote = Text.safe(quote);
        this.comment = Text.safe(comment);
    }
}
