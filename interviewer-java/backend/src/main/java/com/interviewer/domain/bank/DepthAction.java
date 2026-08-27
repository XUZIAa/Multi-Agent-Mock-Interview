package com.interviewer.domain.bank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.interviewer.core.type.Labeled;

/**
 * 答完一题后的确定性推进动作。模型只提供质量分，动作由规则算。
 *
 * <p>这是「面试官不会在你确实不会的点上一直折磨你，但分数会体现出来」这条行为的
 * 载体。把它交给模型自由裁量，就会退化成一路顺着候选人走。
 */
public enum DepthAction implements Labeled {

    DEEPEN("deepen", "答得住，往下深一层"),
    SIDESTEP("sidestep", "答得一般，同层换角度再确认"),
    SWITCH("switch", "这块问够了，换领域"),
    ABANDON("abandon", "明显答不上来，停止深挖并记低分");

    private final String value;
    private final String label;

    DepthAction(String value, String label) {
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
    public static DepthAction of(String raw) {
        return Labeled.find(DepthAction.class, raw).orElse(null);
    }

    /** 这两个动作意味着「别在这个领域待了」，会强制关掉追问。 */
    public boolean leavesTopic() {
        return this == SWITCH || this == ABANDON;
    }
}
