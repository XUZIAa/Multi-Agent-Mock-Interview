package com.interviewer.domain.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import java.util.List;

/** 满分答案重构。用候选人自己的经历改写，不是通用模板。 */
public record AnswerRewrite(int questionIndex, String question, String original,
                            String rewritten, List<String> whyBetter,
                            List<String> usedAssets) {

    @JsonCreator
    public AnswerRewrite(@JsonProperty("question_index") int questionIndex,
                         @JsonProperty("question") String question,
                         @JsonProperty("original") String original,
                         @JsonProperty("rewritten") String rewritten,
                         @JsonProperty("why_better") List<String> whyBetter,
                         @JsonProperty("used_assets") List<String> usedAssets) {
        this.questionIndex = Math.max(0, questionIndex);
        this.question = Text.safe(question);
        this.original = Text.safe(original);
        this.rewritten = Text.safe(rewritten);
        this.whyBetter = whyBetter == null ? List.of() : List.copyOf(whyBetter);
        this.usedAssets = usedAssets == null ? List.of() : List.copyOf(usedAssets);
    }
}
