package com.interviewer.core.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/** 面试阶段，导演据此收敛提问范围。 */
public enum InterviewPhase implements Labeled {

    WARMUP("warmup", "开场破冰"),
    RESUME_DEEP_DIVE("resume_deep_dive", "简历深挖"),
    TECH_DEPTH("tech_depth", "技术深度"),
    BEHAVIORAL("behavioral", "行为面试"),
    CODING("coding", "编码环节"),
    STRESS("stress", "压力测试"),
    CANDIDATE_QA("candidate_qa", "候选人提问"),
    CLOSING("closing", "面试收尾"),
    FINISHED("finished", "已结束");

    /** 阶段推进顺序。FINISHED 不在其中：它是终点，不是可排期的环节。 */
    public static final List<InterviewPhase> ORDER = List.of(
            WARMUP, RESUME_DEEP_DIVE, TECH_DEPTH, BEHAVIORAL,
            CODING, STRESS, CANDIDATE_QA, CLOSING);

    private final String value;
    private final String label;

    InterviewPhase(String value, String label) {
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
    public static InterviewPhase of(String raw) {
        return Labeled.parse(InterviewPhase.class, raw, WARMUP);
    }
}
