package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.Labeled;
import com.interviewer.core.type.StarElement;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 行为题的 STAR 完整度判定。近线执行，不阻塞对话。
 *
 * <p>结果只用来决定下一轮要不要 STAR_PROBE，以及界面上那四个格子亮几个。判不出来就不判，
 * 面试照常走。
 */
@Component
public class StarAnalyst extends Agent {

    /** 太短的回答谈不上 STAR，省一次调用。 */
    private static final int MIN_ANSWER = 20;
    private static final Duration TIMEOUT = Duration.ofMillis(6000);

    private final AgentTasks tasks;

    public StarAnalyst(LlmRouter router, AgentTasks tasks) {
        super(router);
        this.tasks = tasks;
    }

    @Override
    protected String role() {
        return Providers.ROLE_ASSIST;
    }

    /** weakest 为 null 表示四个要素都齐了。 */
    public record Verdict(List<StarElement> present, StarElement weakest, String probeHint) {
    }

    record Raw(@JsonProperty("present") List<String> present,
               @JsonProperty("weakest") String weakest,
               @JsonProperty("probe_hint") String probeHint) {

        Raw(List<String> present, String weakest, String probeHint) {
            this.present = present == null ? List.of() : List.copyOf(present);
            this.weakest = weakest;
            this.probeHint = Text.safe(probeHint);
        }
    }

    public Verdict analyze(String question, String answer) {
        if (Text.safe(answer).strip().length() < MIN_ANSWER) {
            return null;
        }
        Raw raw = tasks.withTimeout(TIMEOUT,
                () -> client().structured(
                        List.of(new SystemMessage(Prompts.STAR_SYSTEM),
                                new UserMessage(Prompts.starUser(question, answer))),
                        Raw.class, 0.1, 400, 0),
                null, "STAR 判定");
        if (raw == null) {
            return null;
        }

        List<StarElement> present = new ArrayList<>();
        for (String item : raw.present()) {
            StarElement element = Labeled.find(StarElement.class, item).orElse(null);
            if (element != null && !present.contains(element)) {
                present.add(element);
            }
        }

        StarElement weakest = Labeled.find(StarElement.class, raw.weakest()).orElse(null);
        if (weakest == null) {
            // 模型没指名最弱项时，按声明顺序取第一个缺的
            weakest = Arrays.stream(StarElement.values())
                    .filter(e -> !present.contains(e))
                    .findFirst().orElse(null);
        }
        return new Verdict(List.copyOf(present), weakest, Text.trim(raw.probeHint(), 80));
    }
}
