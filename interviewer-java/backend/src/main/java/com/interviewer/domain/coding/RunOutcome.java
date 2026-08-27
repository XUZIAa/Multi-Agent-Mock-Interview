package com.interviewer.domain.coding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;

/** 一次自由运行的结果。ok 只代表进程正常退出，不代表答案对。 */
public record RunOutcome(boolean ok, String stdout, String stderr, int exitCode,
                         long durationMs, boolean timedOut) {

    @JsonCreator
    public RunOutcome(@JsonProperty("ok") boolean ok,
                      @JsonProperty("stdout") String stdout,
                      @JsonProperty("stderr") String stderr,
                      @JsonProperty("exit_code") int exitCode,
                      @JsonProperty("duration_ms") long durationMs,
                      @JsonProperty("timed_out") boolean timedOut) {
        this.ok = ok;
        this.stdout = Text.safe(stdout);
        this.stderr = Text.safe(stderr);
        this.exitCode = exitCode;
        this.durationMs = durationMs;
        this.timedOut = timedOut;
    }

    public static RunOutcome timeout(String message, long durationMs) {
        return new RunOutcome(false, "", message, -1, durationMs, true);
    }
}
