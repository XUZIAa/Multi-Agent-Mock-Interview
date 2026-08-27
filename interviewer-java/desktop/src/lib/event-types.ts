// 由 tools/gen_frontend_types.py 生成，请勿手改。
// 事件名与载荷来自后端 OpenAPI 文档的 x-interviewer-events 扩展字段。

import type { components } from "./api-schema";

type Payloads = components["schemas"];

export type AudioLevel = Payloads["AudioLevel"];
export type CodeSubmitted = Payloads["CodeSubmitted"];
export type CopilotHint = Payloads["CopilotHint"];
export type DirectorDecided = Payloads["DirectorDecided"];
export type DriftDetected = Payloads["DriftDetected"];
export type ElapsedTick = Payloads["ElapsedTick"];
export type EngineFailure = Payloads["EngineFailure"];
export type InterruptionFired = Payloads["InterruptionFired"];
export type InterviewerSpeaking = Payloads["InterviewerSpeaking"];
export type LiveAnnotation = Payloads["LiveAnnotation"];
export type LiveScoreUpdated = Payloads["LiveScoreUpdated"];
export type PhaseChanged = Payloads["PhaseChanged"];
export type ProsodySnapshot = Payloads["ProsodySnapshot"];
export type RealtimeStateChanged = Payloads["RealtimeStateChanged"];
export type ReanchorPerformed = Payloads["ReanchorPerformed"];
export type ReviewProgress = Payloads["ReviewProgress"];
export type SpeechActivity = Payloads["SpeechActivity"];
export type StarProgress = Payloads["StarProgress"];
export type TaskProgress = Payloads["TaskProgress"];
export type TranscriptCommitted = Payloads["TranscriptCommitted"];
export type TranscriptDelta = Payloads["TranscriptDelta"];

/** 事件名到载荷的映射，供 onEvent 做类型推断。 */
export interface EventMap {
  audio_level: AudioLevel;
  code_submitted: CodeSubmitted;
  copilot_hint: CopilotHint;
  director_decided: DirectorDecided;
  drift_detected: DriftDetected;
  elapsed_tick: ElapsedTick;
  engine_failure: EngineFailure;
  interruption_fired: InterruptionFired;
  interviewer_speaking: InterviewerSpeaking;
  live_annotation: LiveAnnotation;
  live_score_updated: LiveScoreUpdated;
  phase_changed: PhaseChanged;
  prosody_snapshot: ProsodySnapshot;
  realtime_state_changed: RealtimeStateChanged;
  reanchor_performed: ReanchorPerformed;
  review_progress: ReviewProgress;
  speech_activity: SpeechActivity;
  star_progress: StarProgress;
  task_progress: TaskProgress;
  transcript_committed: TranscriptCommitted;
  transcript_delta: TranscriptDelta;
}

export type EventName = keyof EventMap;
