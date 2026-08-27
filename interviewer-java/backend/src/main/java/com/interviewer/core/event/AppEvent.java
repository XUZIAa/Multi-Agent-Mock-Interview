package com.interviewer.core.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.type.AnnotationKind;
import com.interviewer.core.type.DriftKind;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.core.type.StarElement;
import com.interviewer.core.type.TurnIntent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 所有事件的封闭家族。
 *
 * <p>集中在一个文件里，与 Python 版的 core/events.py 一一对应：少一个前端就瞎一块，
 * 放在一起才好核对。事件名由类名推导（AudioLevel -> audio_level），前端按这个名字分派，
 * 所以类名就是契约，改名等于改接口。
 */
public sealed interface AppEvent {

    /** AudioLevel -> audio_level。与 Python 版 hub.event_name 同一套规则。 */
    static String nameOf(Class<? extends AppEvent> type) {
        return type.getSimpleName().replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
    }

    default String eventName() {
        return nameOf(getClass());
    }

    record RealtimeStateChanged(boolean connected, String reason) implements AppEvent {
    }

    /** VAD 判定的说话起止，用于 UI 高亮当前发言人。 */
    record SpeechActivity(boolean speaking) implements AppEvent {
    }

    /** 0.0~1.0 的瞬时能量，驱动波形动画。 */
    record AudioLevel(double candidate, double interviewer) implements AppEvent {
    }

    /**
     * 面试官是否正在发言。
     *
     * <p>以「响应未结束或缓冲里还有音频」为界，不用瞬时电平：音频块之间的自然
     * 间隙会让电平归零，UI 状态就会在两种文案之间来回跳。
     */
    record InterviewerSpeaking(boolean speaking) implements AppEvent {
    }

    record TranscriptDelta(String speaker, String text) implements AppEvent {
    }

    record TranscriptCommitted(int turnId, String speaker, String text,
                               long startedAtMs, long durationMs) implements AppEvent {
    }

    record PhaseChanged(InterviewPhase phase, String reason) implements AppEvent {
    }

    record DirectorDecided(TurnIntent intent, String brief, String targetSkill,
                           int followUpDepth) implements AppEvent {
    }

    record DriftDetected(DriftKind kind, String excerpt, boolean repaired) implements AppEvent {
    }

    record ReanchorPerformed(int turnIndex, String trigger) implements AppEvent {
    }

    record CopilotHint(List<String> keywords, List<String> outline, String caution)
            implements AppEvent {
    }

    // Jackson 会把 isBehavioral 当 bean 式布尔读取器剥掉 is 前缀，落成 behavioral
    record StarProgress(Set<StarElement> present, Set<StarElement> missing,
                        @JsonProperty("is_behavioral") boolean isBehavioral) implements AppEvent {
    }

    record InterruptionFired(boolean byInterviewer, String reason) implements AppEvent {
    }

    record LiveAnnotation(int turnId, AnnotationKind kind, String comment) implements AppEvent {
    }

    record LiveScoreUpdated(Map<ScoreDimension, Double> scores) implements AppEvent {
    }

    record CodeSubmitted(String language, String source) implements AppEvent {
    }

    record EngineFailure(String userMessage, String detail, boolean fatal) implements AppEvent {

        public EngineFailure(String userMessage) {
            this(userMessage, "", false);
        }

        public EngineFailure(String userMessage, String detail) {
            this(userMessage, detail, false);
        }
    }

    record ProsodySnapshot(double wordsPerMinute, double fillerRatio, double pauseRatio,
                           long longestPauseMs) implements AppEvent {
    }

    record ReviewProgress(String stage, int percent, String detail) implements AppEvent {

        public ReviewProgress(String stage, int percent) {
            this(stage, percent, "");
        }
    }

    record ElapsedTick(long elapsedMs, long remainingMs) implements AppEvent {
    }

    /**
     * 长任务进度。
     *
     * <p>它不来自编排层，由接口层按 task_id 回推——出题、解析简历都要几十秒，
     * 进度塞不进 HTTP 响应。
     */
    record TaskProgress(String taskId, String stage, int percent) implements AppEvent {
    }
}
