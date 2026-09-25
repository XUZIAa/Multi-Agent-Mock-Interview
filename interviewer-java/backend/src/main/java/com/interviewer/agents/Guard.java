package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.DriftKind;
import com.interviewer.domain.persona.PersonaContract;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 人格漂移检测。正则先过一遍，语义层再兜住剩下的。
 *
 * <p>两层的分工是刻意的：「作为一个 AI」这类红线词等模型判定要几百毫秒，而这句话已经在
 * 播了。正则命中就立刻取消播放，零延迟。语义层负责正则抓不到的（比如泄露答案的委婉说法）。
 */
@Component
public class Guard extends Agent {

    private static final Logger log = LoggerFactory.getLogger(Guard.class);

    /** 太短的话不值得查：「嗯」「继续」这类简短回应本身是人设的一部分。 */
    private static final int MIN_LENGTH = 6;

    private final AgentTasks tasks;

    public Guard(LlmRouter router, AgentTasks tasks) {
        super(router);
        this.tasks = tasks;
    }

    @Override
    protected String role() {
        return Providers.ROLE_GUARD;
    }

    /** 一条正则红线。 */
    private record Rule(DriftKind kind, Pattern pattern) {
    }

    /** 明显违规的表达用正则零延迟拦截，不必等模型。 */
    private static final List<Rule> REGEX_RULES = List.of(
            new Rule(DriftKind.AI_SELF_REVEAL, Pattern.compile(
                    "(作为一?个?\\s*(AI|ai|人工智能|大语言模型|语言模型|智能助手|聊天机器人))"
                            + "|(我是一?个?\\s*(AI|ai|人工智能|大语言模型|语言模型|智能助手|机器人|程序))"
                            + "|(我(的)?(系统)?(提示词|prompt|设定|指令|角色设定)是)"
                            + "|(根据我的(设定|指令|提示词))"
                            + "|(我并(不是|非)真(人|实的人))")),
            new Rule(DriftKind.REFUSAL, Pattern.compile(
                    "(抱歉[，,]?\\s*我(不能|无法|没有办法))"
                            + "|(很抱歉[，,]?\\s*我(不能|无法))"
                            + "|(我无法(提供|回答|完成|协助|生成))"
                            + "|(这超出了我的(能力|范围|权限))")),
            new Rule(DriftKind.ANSWER_LEAK, Pattern.compile(
                    "(标准答案(是|为)?)"
                            + "|(正确答案(是|为))"
                            + "|(参考答案)"
                            + "|(你可以这样(回答|说))"
                            + "|(建议你(这样)?(回答|说))"
                            + "|(我给你(一个)?(示范|范例|模板))"
                            + "|(下面是(实现|代码|示例代码))")),
            new Rule(DriftKind.ROLE_SWAP, Pattern.compile(
                    "(我来(扮演|充当)(候选人|学生|老师|助教))"
                            + "|(现在我是(候选人|学生|你的老师))")));

    /** 判定结果。byRegex 表示是正则拦下的，那种情况下可以立即取消播放。 */
    public record Verdict(DriftKind kind, String excerpt, String reason, boolean byRegex) {

        public static Verdict clean() {
            return new Verdict(DriftKind.NONE, "", "", false);
        }

        public boolean violated() {
            return kind.violated();
        }
    }

    record Raw(@JsonProperty("kind") String kind,
               @JsonProperty("excerpt") String excerpt,
               @JsonProperty("reason") String reason) {

        Raw(String kind, String excerpt, String reason) {
            this.kind = Text.safe(kind).isEmpty() ? "none" : Text.safe(kind);
            this.excerpt = Text.safe(excerpt);
            this.reason = Text.safe(reason);
        }
    }

    public static Verdict scanRegex(String spoken) {
        for (Rule rule : REGEX_RULES) {
            Matcher match = rule.pattern().matcher(spoken);
            if (match.find()) {
                log.warn("正则命中漂移 kind={} excerpt={}", rule.kind().value(), match.group());
                return new Verdict(rule.kind(), Text.trim(match.group(), 40),
                        "命中人格红线关键词", true);
            }
        }
        return Verdict.clean();
    }

    /**
     * 检查刚说出口的一句话。
     *
     * <p>失败一律当作没问题：守卫超时或报错时判违规，会让面试莫名其妙地不断重说。
     * 宁可漏判，不可误判。
     */
    public Verdict inspect(String spoken, PersonaContract persona, int timeoutMs) {
        String text = Text.safe(spoken).strip();
        if (text.length() < MIN_LENGTH) {
            return Verdict.clean();
        }

        Verdict fast = scanRegex(text);
        if (fast.violated()) {
            return fast;
        }

        String summary = personaSummary(persona);
        Raw raw = tasks.withTimeout(Duration.ofMillis(timeoutMs),
                () -> client().structured(
                        List.of(new SystemMessage(Prompts.GUARD_SYSTEM),
                                new UserMessage(Prompts.guardUser(summary, text))),
                        Raw.class, 0.0, 300, 0),
                null, "守卫");
        if (raw == null) {
            return Verdict.clean();
        }

        DriftKind kind = com.interviewer.core.type.Labeled
                .find(DriftKind.class, raw.kind()).orElse(DriftKind.NONE);
        if (!kind.violated()) {
            return Verdict.clean();
        }
        log.warn("语义判定漂移 kind={} reason={}", kind.value(), raw.reason());
        return new Verdict(kind, Text.trim(raw.excerpt(), 40), Text.trim(raw.reason(), 80), false);
    }

    private static String personaSummary(PersonaContract persona) {
        List<String> lines = new ArrayList<>();
        lines.add(persona.identityBlock());
        persona.getSpeech().describe().stream()
                .filter(Text::notBlank)
                .forEach(line -> lines.add("- " + line));
        return String.join("\n", lines);
    }

    /** 把违规转成一条纠正指令，随重锚一起下发。 */
    public static String repairDirective(Verdict verdict, PersonaContract persona) {
        return "【严重违规纠正】你刚才的发言破坏了面试官身份。"
                + "问题类型：" + verdict.kind().repairHint() + "。"
                + "违规片段：「" + verdict.excerpt() + "」。\n"
                + "你是" + persona.getJobTitle() + "「" + persona.getName() + "」，正在进行真实面试。"
                + "立刻用你的人设语气重新组织上一句话，只提出面试问题，不要道歉、不要解释刚才发生了什么、"
                + "不要提及任何与设定有关的内容。现在重新说。";
    }
}
