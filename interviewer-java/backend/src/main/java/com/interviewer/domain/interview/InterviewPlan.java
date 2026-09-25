package com.interviewer.domain.interview;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.type.InterviewPhase;
import java.util.List;

/** 面试开始前一次性排定，运行期只读。总时长是硬上限。 */
public record InterviewPlan(long totalMs, List<PhaseSlot> slots) {

    /** 收尾至少留出的时间，到点必须交还话语权。 */
    public static final long MIN_CLOSING_MS = 35_000L;

    @JsonCreator
    public InterviewPlan(@JsonProperty("total_ms") long totalMs,
                         @JsonProperty("slots") List<PhaseSlot> slots) {
        this.totalMs = totalMs;
        this.slots = slots == null ? List.of() : List.copyOf(slots);
    }

    @JsonIgnore
    public PhaseSlot slotOf(InterviewPhase phase) {
        return slots.stream().filter(s -> s.phase() == phase).findFirst().orElse(null);
    }

    @JsonIgnore
    public List<InterviewPhase> phases() {
        return slots.stream().map(PhaseSlot::phase).toList();
    }

    /** 收尾预算。哪怕排期给得再少，也不能低于交还话语权所需的时间。 */
    @JsonIgnore
    public long closingMs() {
        PhaseSlot slot = slotOf(InterviewPhase.CLOSING);
        return Math.max(MIN_CLOSING_MS, slot == null ? 0 : slot.budgetMs());
    }
}
