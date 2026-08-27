package com.interviewer.domain.bank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.type.Depth;
import com.interviewer.core.type.QuestionSource;
import java.util.List;

/**
 * 题库里的一道题。面试中问出的每个新问题都必须来自这里。
 *
 * <p>导演不能自由生成题目——那样问出来的东西对不上 JD 也对不上简历。
 */
public record BankQuestion(int id, String text, String skill, String domain, int depth,
                           QuestionSource source, String projectRef, String jdRef,
                           List<String> followUps, List<String> expectedSignals,
                           boolean mustAsk) {

    @JsonCreator
    public BankQuestion(@JsonProperty("id") int id,
                        @JsonProperty("text") String text,
                        @JsonProperty("skill") String skill,
                        @JsonProperty("domain") String domain,
                        @JsonProperty("depth") int depth,
                        @JsonProperty("source") QuestionSource source,
                        @JsonProperty("project_ref") String projectRef,
                        @JsonProperty("jd_ref") String jdRef,
                        @JsonProperty("follow_ups") List<String> followUps,
                        @JsonProperty("expected_signals") List<String> expectedSignals,
                        @JsonProperty("must_ask") boolean mustAsk) {
        this.id = id;
        this.text = Text.safe(text);
        this.skill = Text.safe(skill);
        this.domain = Text.safe(domain);
        this.depth = Depth.clamp(depth == 0 ? 1 : depth);
        this.source = source == null ? QuestionSource.FUNDAMENTAL : source;
        this.projectRef = Text.safe(projectRef);
        this.jdRef = Text.safe(jdRef);
        this.followUps = followUps == null ? List.of() : List.copyOf(followUps);
        this.expectedSignals = expectedSignals == null ? List.of() : List.copyOf(expectedSignals);
        this.mustAsk = mustAsk;
    }

    /**
     * 给导演选题时看的，带上关联出处便于判断该不该问这道。
     *
     * <p>不要直接下发给语音：jdRef 存的是 JD 原文，写法通常是「熟悉 XXX」，
     * 面试官会把它当成候选人的自述念出来。
     */
    public String briefForDirector() {
        StringBuilder sb = new StringBuilder(text);
        if (!projectRef.isEmpty()) {
            sb.append("（关联他简历里的「").append(projectRef).append("」）");
        }
        if (!jdRef.isEmpty()) {
            sb.append("（对应 JD 要求：").append(jdRef).append("）");
        }
        return sb.toString();
    }

    /** 下发给语音的版本。只留题面，附带能安全说出口的项目名。 */
    public String briefForVoice() {
        if (!projectRef.isEmpty()) {
            return text + "（这道题针对他简历里的「" + projectRef + "」，可以点名这个项目）";
        }
        return text;
    }

    public String oneLine() {
        String head = text.length() > 60 ? text.substring(0, 60) : text;
        return "[" + id + "] D" + depth + " " + domain + "/" + skill + "｜" + head;
    }

    public BankQuestion withMustAsk(boolean value) {
        return new BankQuestion(id, text, skill, domain, depth, source, projectRef, jdRef,
                followUps, expectedSignals, value);
    }
}
