package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.provider.Providers;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 卡壳时给抓手。必须快，超时就不给，绝不让用户等。
 *
 * <p>只给关键词和提纲，不给能背的整句：目的是让人自己组织语言，而不是念稿。
 */
@Component
public class Copilot extends Agent {

    private static final Duration TIMEOUT = Duration.ofMillis(9000);

    private final AgentTasks tasks;

    public Copilot(LlmRouter router, AgentTasks tasks) {
        super(router);
        this.tasks = tasks;
    }

    @Override
    protected String role() {
        return Providers.ROLE_ASSIST;
    }

    /** 空载荷表示没给出提示，界面据此显示「稍后再试」而不是空面板。 */
    public record Hint(List<String> keywords, List<String> outline, String caution) {

        public static Hint empty() {
            return new Hint(List.of(), List.of(), "");
        }

        public boolean isEmpty() {
            return keywords.isEmpty() && outline.isEmpty();
        }
    }

    record Raw(@JsonProperty("keywords") List<String> keywords,
               @JsonProperty("outline") List<String> outline,
               @JsonProperty("caution") String caution) {

        Raw(List<String> keywords, List<String> outline, String caution) {
            this.keywords = keywords == null ? List.of() : List.copyOf(keywords);
            this.outline = outline == null ? List.of() : List.copyOf(outline);
            this.caution = Text.safe(caution);
        }
    }

    public Hint hint(String question, String partialAnswer, String resumeDigest) {
        if (!Text.notBlank(question)) {
            return Hint.empty();
        }
        Raw raw = tasks.withTimeout(TIMEOUT,
                () -> client().structured(
                        List.of(new SystemMessage(Prompts.COPILOT_SYSTEM),
                                new UserMessage(Prompts.copilotUser(
                                        question, partialAnswer, resumeDigest))),
                        Raw.class, 0.4, 600, 0),
                null, "提词器");
        if (raw == null) {
            return Hint.empty();
        }
        return new Hint(
                cap(raw.keywords(), 7, 12),
                cap(raw.outline(), 4, 40),
                Text.trim(raw.caution(), 50));
    }

    private static List<String> cap(List<String> items, int count, int width) {
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (Text.notBlank(item) && out.size() < count) {
                out.add(Text.trim(item, width));
            }
        }
        return List.copyOf(out);
    }
}
