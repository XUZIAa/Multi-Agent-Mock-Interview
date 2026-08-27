package com.interviewer.core.config;

import lombok.Getter;

/** 编排引擎的可调参数。这些值决定节奏，不决定内容。 */
@Getter
public class OrchestrationSettings {

    private int reanchorEveryTurns = 4;
    private int maxFollowUpDepth = 3;
    private int directorTimeoutMs = 20000;
    private int guardTimeoutMs = 2500;
    private int interruptBudgetPerPhase = 2;
    private double verboseSecondsBeforeInterrupt = 42.0;

    /** 候选人停止说话后等多久算一个回合结束。 */
    private int turnGapMs = 850;

    private int plannedMinutes = 30;

    public void setReanchorEveryTurns(int value) {
        this.reanchorEveryTurns = Clamp.of(value, 1, 20);
    }

    public void setMaxFollowUpDepth(int value) {
        this.maxFollowUpDepth = Clamp.of(value, 1, 6);
    }

    public void setDirectorTimeoutMs(int value) {
        this.directorTimeoutMs = Clamp.of(value, 2000, 60000);
    }

    public void setGuardTimeoutMs(int value) {
        this.guardTimeoutMs = Clamp.of(value, 800, 10000);
    }

    public void setInterruptBudgetPerPhase(int value) {
        this.interruptBudgetPerPhase = Clamp.of(value, 0, 10);
    }

    public void setVerboseSecondsBeforeInterrupt(double value) {
        this.verboseSecondsBeforeInterrupt = Clamp.of(value, 10.0, 180.0);
    }

    public void setTurnGapMs(int value) {
        this.turnGapMs = Clamp.of(value, 300, 3000);
    }

    public void setPlannedMinutes(int value) {
        this.plannedMinutes = Clamp.of(value, 10, 45);
    }
}
