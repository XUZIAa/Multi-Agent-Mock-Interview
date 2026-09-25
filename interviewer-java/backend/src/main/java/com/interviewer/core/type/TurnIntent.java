package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Set;

/** 导演下发给实时模型的意图，实时模型只负责用人设语气表达。 */
public enum TurnIntent implements Labeled {

    ASK_NEW("ask_new", "提出新问题"),
    FOLLOW_UP("follow_up", "顺着回答追问"),
    STAR_PROBE("star_probe", "引导补全 STAR"),
    BOUNDARY_TEST("boundary_test", "边界与极限测试"),
    INTERRUPT("interrupt", "打断候选人"),
    PRESSURE("pressure", "施加压力"),
    ACKNOWLEDGE("acknowledge", "简短回应"),
    TRANSITION("transition", "切换环节"),
    CODING_HANDOFF("coding_handoff", "移交编码环节"),
    CLOSE("close", "结束面试");

    /** 会开启一道新题的意图。它们必须从题库里选题。 */
    public static final Set<TurnIntent> NEW_QUESTION = Set.of(ASK_NEW, CODING_HANDOFF);

    /** 追问类意图，受追问深度上限约束。 */
    public static final Set<TurnIntent> PROBE = Set.of(FOLLOW_UP, STAR_PROBE, BOUNDARY_TEST);

    private final String value;
    private final String label;

    TurnIntent(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static TurnIntent of(String raw) {
        return Labeled.parse(TurnIntent.class, raw, ACKNOWLEDGE);
    }

    public boolean opensNewQuestion() {
        return NEW_QUESTION.contains(this);
    }

    public boolean isProbe() {
        return PROBE.contains(this);
    }
}
