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
 * 读候选人的代码，产出一个真人式的追问。不给答案。
 *
 * <p>用导演的模型而不是助手的：这活儿要真读懂代码，比提词器难。
 */
@Component
public class CodeExaminer extends Agent {

    private static final int MIN_SOURCE = 20;
    private static final Duration TIMEOUT = Duration.ofMillis(25000);
    private static final String DEFAULT_KIND = "correctness";

    private final AgentTasks tasks;

    public CodeExaminer(LlmRouter router, AgentTasks tasks) {
        super(router);
        this.tasks = tasks;
    }

    @Override
    protected String role() {
        return Providers.ROLE_DIRECTOR;
    }

    /** probe 里不得含答案或修改建议，只能提问——提示词里也钉了这条。 */
    public record Probe(String verdict, String complexity, String probe, String probeKind,
                        List<String> issues, double quality) {
    }

    record Raw(@JsonProperty("verdict") String verdict,
               @JsonProperty("complexity") String complexity,
               @JsonProperty("probe") String probe,
               @JsonProperty("probe_kind") String probeKind,
               @JsonProperty("issues") List<String> issues,
               @JsonProperty("quality") double quality) {

        Raw(String verdict, String complexity, String probe, String probeKind,
            List<String> issues, double quality) {
            this.verdict = Text.safe(verdict);
            this.complexity = Text.safe(complexity);
            this.probe = Text.safe(probe);
            this.probeKind = Text.safe(probeKind);
            this.issues = issues == null ? List.of() : List.copyOf(issues);
            this.quality = quality;
        }
    }

    public Probe probe(String language, String source, String problem) {
        if (Text.safe(source).strip().length() < MIN_SOURCE) {
            return null;
        }
        Raw raw = tasks.withTimeout(TIMEOUT,
                () -> client().structured(
                        List.of(new SystemMessage(Prompts.CODE_PROBE_SYSTEM),
                                new UserMessage(Prompts.codeProbeUser(language, source, problem))),
                        Raw.class, 0.3, 900, 1),
                null, "代码追问");
        if (raw == null) {
            return null;
        }
        String kind = raw.probeKind().strip().toLowerCase(java.util.Locale.ROOT);
        List<String> issues = new ArrayList<>();
        for (String issue : raw.issues()) {
            if (Text.notBlank(issue) && issues.size() < 4) {
                issues.add(Text.trim(issue, 30));
            }
        }
        return new Probe(
                Text.trim(raw.verdict(), 80),
                Text.trim(raw.complexity(), 40),
                Text.trim(raw.probe(), 120),
                kind.isEmpty() ? DEFAULT_KIND : kind,
                List.copyOf(issues),
                Text.clamp01(raw.quality()));
    }
}
