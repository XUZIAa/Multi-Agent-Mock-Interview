package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.provider.Providers;
import com.interviewer.domain.coding.CodingCase;
import com.interviewer.domain.coding.CodingChallenge;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 出一道能自动判题的编码题。
 *
 * <p>与题库分开：题库的口述题只要题干，这里必须凑齐输入输出格式、起始代码、用例和参考答案，
 * 缺一样前端就没法判题。
 */
@Component
public class CodingComposer extends Agent {

    private static final int MAX_CASES = 6;
    private static final Duration TIMEOUT = Duration.ofMillis(90000);

    private final AgentTasks tasks;

    public CodingComposer(LlmRouter router, AgentTasks tasks) {
        super(router);
        this.tasks = tasks;
    }

    @Override
    protected String role() {
        return Providers.ROLE_ANALYST;
    }

    record CaseRaw(@JsonProperty("input") String input,
                   @JsonProperty("expected") String expected,
                   @JsonProperty("note") String note) {

        CaseRaw(String input, String expected, String note) {
            this.input = Text.safe(input);
            this.expected = Text.safe(expected);
            this.note = Text.safe(note);
        }
    }

    record Raw(@JsonProperty("title") String title,
               @JsonProperty("statement") String statement,
               @JsonProperty("io_format") String ioFormat,
               @JsonProperty("starter_python") String starterPython,
               @JsonProperty("starter_javascript") String starterJavascript,
               @JsonProperty("reference_python") String referencePython,
               @JsonProperty("reference_javascript") String referenceJavascript,
               @JsonProperty("cases") List<CaseRaw> cases,
               @JsonProperty("hints") List<String> hints) {

        Raw(String title, String statement, String ioFormat, String starterPython,
            String starterJavascript, String referencePython, String referenceJavascript,
            List<CaseRaw> cases, List<String> hints) {
            this.title = Text.safe(title);
            this.statement = Text.safe(statement);
            this.ioFormat = Text.safe(ioFormat);
            this.starterPython = Text.safe(starterPython);
            this.starterJavascript = Text.safe(starterJavascript);
            this.referencePython = Text.safe(referencePython);
            this.referenceJavascript = Text.safe(referenceJavascript);
            this.cases = cases == null ? List.of() : List.copyOf(cases);
            this.hints = hints == null ? List.of() : List.copyOf(hints);
        }
    }

    public CodingChallenge compose(String skill, String jobTitle, String levelExpectation,
                                   int minutes) {
        Raw raw = tasks.withTimeout(TIMEOUT,
                () -> client().structured(
                        List.of(new SystemMessage(Prompts.CODING_COMPOSE_SYSTEM),
                                new UserMessage(Prompts.codingComposeUser(
                                        skill, jobTitle, levelExpectation, minutes))),
                        Raw.class, 0.4, 2600, 1),
                null, "编码出题");
        if (raw == null) {
            throw new com.interviewer.core.error.ProviderResponseException(
                    "编码出题超时", "出题没能及时完成，稍后再试或换个模型");
        }

        // 判题只比字符串，行尾空白会让正确答案被判错，所以入库前先剪掉
        List<CodingCase> cases = new ArrayList<>();
        for (CaseRaw c : raw.cases()) {
            if (!Text.notBlank(c.expected()) || cases.size() >= MAX_CASES) {
                continue;
            }
            cases.add(new CodingCase(stripTrailing(c.input()), stripTrailing(c.expected()),
                    Text.trim(c.note(), 60)));
        }

        List<String> hints = new ArrayList<>();
        for (String hint : raw.hints()) {
            if (Text.notBlank(hint) && hints.size() < 3) {
                hints.add(Text.trim(hint, 80));
            }
        }

        Map<String, String> starter = new LinkedHashMap<>();
        starter.put("python", raw.starterPython().strip());
        starter.put("javascript", raw.starterJavascript().strip());
        Map<String, String> reference = new LinkedHashMap<>();
        reference.put("python", raw.referencePython().strip());
        reference.put("javascript", raw.referenceJavascript().strip());

        String title = Text.trim(raw.title(), 60);
        return new CodingChallenge(
                title.isEmpty() ? skill + " 编码题" : title,
                raw.statement().strip(), raw.ioFormat().strip(),
                starter, reference, cases, hints);
    }

    private static String stripTrailing(String text) {
        String value = Text.safe(text);
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
