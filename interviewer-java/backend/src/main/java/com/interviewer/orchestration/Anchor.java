package com.interviewer.orchestration;

import com.interviewer.core.Text;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.persona.PersonaContract;
import com.interviewer.llm.PromptResource;
import java.util.ArrayList;
import java.util.List;

/**
 * 人格锚点与导演指令的编译。
 *
 * <p>实时语音模型的音频历史会滚动丢弃，长面试必然被截断。所以人格不靠「模型记得」，
 * 而是每次重锚时把身份、铁律、风格、权威进度整体重发一遍。漂移在架构上就不可能长期存在。
 *
 * <p>文案全部放资源文件：这些是逐字调过的，尤其「这几样一次都不许出现」那一段，每一条都对应
 * 一个真实出现过的坏行为。
 */
public final class Anchor {

    /**
     * 指令前缀。
     *
     * <p>面试官收到的是「自己的内部备忘」而不是候选人的话——这个区分靠这个标记建立，
     * 改了它工作方式那一段就对不上了。
     */
    public static final String DIRECTIVE_TAG = "【导演指令】";

    private static final String WORKFLOW = PromptResource.load("anchor_workflow");

    private Anchor() {
    }

    /** 编译完整人格锚点。每次重锚都整体重发，模型的记忆不参与身份维持。 */
    public static String buildInstructions(InterviewState state) {
        PersonaContract persona = state.getPersona();
        List<String> blocks = new ArrayList<>();
        blocks.add("# 你的身份");
        blocks.add(persona.identityBlock());
        blocks.add("");
        blocks.add(persona.rulesBlock());
        blocks.add("");
        blocks.add("# 你的表达风格");
        blocks.add(persona.styleBlock());
        blocks.add("");
        blocks.add(WORKFLOW);
        blocks.add("");
        blocks.add("【当前环节】" + phaseGuidance(state.getPhase()));

        String context = state.contextBlock();
        if (Text.notBlank(context)) {
            blocks.add("");
            blocks.add("# 候选人背景（面试前已掌握的资料）");
            blocks.add(context);
        }
        blocks.add("");
        blocks.add("# 权威进度（与你的记忆冲突时，一律以本节为准）");
        blocks.add(state.digest());
        return String.join("\n", blocks);
    }

    static String phaseGuidance(InterviewPhase phase) {
        return PromptResource.load("anchor_phase_" + phase.value());
    }

    static String actionRequirement(TurnIntent intent) {
        return PromptResource.load("anchor_action_" + intent.value());
    }

    /** 开场统一邀请候选人自我介绍，后续只从其自述延伸。 */
    public static String openingDirective(PersonaContract persona) {
        return DIRECTIVE_TAG + "\n"
                + "动作：开场\n"
                + "内容：" + persona.opening() + "\n"
                + "要求：一句或两句内自然打招呼并请他自我介绍，不预设项目、技术、业务产出或考察方向。"
                + "不要讲流程，不要追加其他问题；等候选人介绍完，再从他实际说过的内容里选一个点追问。";
    }

    public static String directiveMessage(TurnIntent intent, String brief) {
        return directiveMessage(intent, brief, "", "", false);
    }

    public static String directiveMessage(TurnIntent intent, String brief, String starHint,
                                          String extraRequirement, boolean switchedTopic) {
        List<String> lines = new ArrayList<>();
        lines.add(DIRECTIVE_TAG);
        lines.add("动作：" + intent.label());
        lines.add("内容：" + brief);
        if (switchedTopic) {
            lines.add("过渡：这题换了方向，可以先用半句话收住上一个话题，别硬切。半句就够，不要评价");
        }
        if (Text.notBlank(starHint)) {
            lines.add("补充：" + starHint);
        }
        String requirement = actionRequirement(intent);
        if (Text.notBlank(extraRequirement)) {
            requirement = requirement + " " + extraRequirement;
        }
        lines.add("要求：" + requirement);
        return String.join("\n", lines);
    }

    /** 候选人迟迟不开口时的轻推。不给答案、不换问题。 */
    public static String nudgeDirective() {
        return DIRECTIVE_TAG + "\n"
                + "动作：候选人长时间没有开口\n"
                + "内容：确认他是不是需要你把问题再说一遍，或者告诉他可以边想边说\n"
                + "要求：只说一句话，符合你的人设语气。不要给答案、不要换问题、不要催促过度。";
    }

    /** 代码追问。已察觉的问题只作为引导依据，不许直接说出口。 */
    public static String codeReviewDirective(String probe, String complexity, List<String> issues) {
        List<String> head = new ArrayList<>();
        issues.stream().limit(3).forEach(head::add);
        String detail = String.join("；", head);

        List<String> lines = new ArrayList<>();
        lines.add(DIRECTIVE_TAG);
        lines.add("动作：针对候选人刚提交的代码追问");
        lines.add("内容：" + probe);
        if (Text.notBlank(complexity)) {
            lines.add("已知复杂度：" + complexity + "（你可以引用，但不要念给他听）");
        }
        if (!detail.isEmpty()) {
            lines.add("你已察觉的问题：" + detail + "（不要直接说出问题，用提问引导他自己发现）");
        }
        lines.add("要求：像真人面试官那样只问一个问题。绝对不要给出修改方案或正确实现。");
        return String.join("\n", lines);
    }

    public static String candidateQuestionDirective(String question) {
        return DIRECTIVE_TAG + "\n"
                + "动作：回答候选人的提问\n"
                + "内容：他刚问了「" + question + "」\n"
                + "要求：以面试官身份简短回答，两三句话，符合你所在公司的设定。回答完可以问他还有没有其他问题。";
    }
}
