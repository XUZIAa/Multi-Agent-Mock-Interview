package com.interviewer.orchestration;

import com.interviewer.agents.CodeExaminer;
import com.interviewer.agents.Copilot;
import com.interviewer.agents.Director;
import com.interviewer.agents.Guard;
import com.interviewer.agents.StarAnalyst;
import com.interviewer.core.Text;
import com.interviewer.core.config.AppSettings;
import com.interviewer.core.config.ConfigStore;
import com.interviewer.core.error.InterviewBusyException;
import com.interviewer.core.error.InterviewerException;
import com.interviewer.core.event.AppEvent;
import com.interviewer.core.event.EventBus;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.core.type.SessionStatus;
import com.interviewer.core.type.Speaker;
import com.interviewer.core.type.StarElement;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.data.repository.SessionRepository;
import com.interviewer.domain.bank.DepthAction;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.QuestionRecord;
import com.interviewer.domain.interview.TurnRecord;
import com.interviewer.domain.turn.DirectorDecision;
import com.interviewer.domain.turn.TurnPlan;
import com.interviewer.voice.VoiceChannel;
import com.interviewer.voice.VoiceChannelFactory;
import com.interviewer.voice.VoiceSink;
import com.interviewer.voice.VoiceStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 双环编排。Fast Loop 是语音链路，Slow Loop 是这里的导演与状态机。
 *
 * <p>全部状态变更都排在 {@link SessionLoop} 的单线程上，所以下面这些字段一个锁都不需要——
 * 与 Python 版靠单事件循环获得的是同一个前提。语音回调、心跳、推进循环都只是往那个线程
 * 投递动作。
 */
@Component
public class InterviewEngine implements VoiceSink {

    private static final Logger log = LoggerFactory.getLogger(InterviewEngine.class);

    private static final Duration TICK = Duration.ofMillis(100);
    private static final int MIN_ANSWER_CHARS = 2;

    /** 面试官说完后候选人的沉默处理：先给提词，再由面试官轻推。 */
    private static final Duration COPILOT_SILENCE = Duration.ofMillis(6500);
    private static final Duration NUDGE_SILENCE = Duration.ofMillis(15000);

    private final EventBus bus;
    private final ConfigStore store;
    private final SessionRepository sessions;
    private final VoiceChannelFactory voiceFactory;
    private final Director director;
    private final Guard guard;
    private final StarAnalyst star;
    private final Copilot copilot;
    private final CodeExaminer code;

    // ---------- 以下字段只在 loop 线程上访问 ----------

    private SessionLoop loop;
    private InterviewState state;
    private VoiceChannel voice;
    private long startedAtNanos;
    private boolean closing;
    private boolean closeTriggered;
    private boolean turnPending;
    private boolean turnRunning;
    private boolean candidateSpeaking;
    private boolean interviewerVoicing;
    private long answerStartedMs;
    private final List<String> pendingAnswer = new ArrayList<>();
    private String starHint = "";

    /** 已经问出口的那道题。导演推进得比语音快，提词器要跟着耳朵走。 */
    private String voicedQuestion = "";

    /** 本轮语音预计真正出声的时刻。播放队列里压着的那几秒就是这个差值。 */
    private long voiceOpenAtNanos;

    /** 打断会清空播放队列，排队中那几轮的语音永远不会出声，展示负载要跟着作废。 */
    private final AtomicInteger cueEpoch = new AtomicInteger();

    private final Map<String, PendingCue> cues = new java.util.HashMap<>();
    private VoiceStatus lastStatus = VoiceStatus.idle();

    private ScheduledFuture<?> turnTimer;
    private ScheduledFuture<?> verbosityTimer;
    private ScheduledFuture<?> silenceTimer;
    private ScheduledFuture<?> tickTimer;

    // ---------- 跨线程可见的收尾信号 ----------

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile CountDownLatch finished = new CountDownLatch(0);
    private volatile InterviewState lastFinished;

    public InterviewEngine(EventBus bus, ConfigStore store, SessionRepository sessions,
                           VoiceChannelFactory voiceFactory, Director director, Guard guard,
                           StarAnalyst star, Copilot copilot, CodeExaminer code) {
        this.bus = bus;
        this.store = store;
        this.sessions = sessions;
        this.voiceFactory = voiceFactory;
        this.director = director;
        this.guard = guard;
        this.star = star;
        this.copilot = copilot;
        this.code = code;
    }

    /**
     * 一轮的展示负载，随指令下行、随语音起播浮现。
     *
     * <p>导演的时间轴比耳朵快一整轮：模型三秒生成十秒音频，缓冲里常压着六到十秒。
     * 决策一出就刷状态栏和提词器，用户会看到下一题、听到上一题。
     */
    private record PendingCue(String question, AppEvent.DirectorDecided event, int epoch) {
    }

    // ---------- 生命周期 ----------

    public boolean isRunning() {
        return running.get();
    }

    public InterviewState state() {
        return state;
    }

    public void start(InterviewState fresh) {
        if (!running.compareAndSet(false, true)) {
            throw new InterviewBusyException();
        }
        finished = new CountDownLatch(1);
        lastFinished = null;
        loop = new SessionLoop(fresh.getSessionId());

        // 启动失败必须把占位彻底还回去，否则引擎永久停在「有面试在进行中」
        try {
            runSync(() -> boot(fresh));
        } catch (RuntimeException e) {
            rollbackStart(fresh);
            throw e;
        }
    }

    private void boot(InterviewState fresh) {
        this.state = fresh;
        this.closing = false;
        this.closeTriggered = false;
        this.voicedQuestion = "";
        this.voiceOpenAtNanos = 0;
        this.pendingAnswer.clear();
        this.starHint = "";
        this.lastStatus = VoiceStatus.idle();
        this.startedAtNanos = System.nanoTime();

        AppSettings settings = store.settings();
        state.enterPhase(InterviewPhase.WARMUP);

        voice = voiceFactory.open(this, fresh.getSessionId());
        sessions.setStatus(state.getSessionId(), SessionStatus.RUNNING);
        voice.connect(Anchor.buildInstructions(state), settings, state.getPersona());

        tickTimer = loop.postPeriodic(TICK, this::onTick);
        warnAudioConfig(settings);
        bus.emit(new AppEvent.PhaseChanged(state.getPhase(), "面试开始"));

        state.openQuestion(TurnIntent.ASK_NEW, "请候选人做一到两分钟的自我介绍", "",
                "候选人经历", 1, null, false);
        voice.sendDirective(Anchor.openingDirective(state.getPersona()),
                stageCue("请你先做一下自我介绍", null));
        log.info("面试开始 session={} persona={}", state.getSessionId(), state.getPersona().getName());
    }

    /** 把 start 已经做出的副作用逐项撤销，让引擎回到可再次开始的空闲态。 */
    private void rollbackStart(InterviewState fresh) {
        try {
            if (voice != null) {
                voice.close("启动失败");
            }
        } catch (Exception ignored) {
            // 已经在失败路径上，不再追加噪音
        }
        voice = null;
        if (loop != null) {
            loop.close();
            loop = null;
        }
        state = null;
        try {
            sessions.setStatus(fresh.getSessionId(), SessionStatus.DRAFT);
        } catch (Exception e) {
            log.warn("回滚会话状态失败: {}", e.getMessage());
        }
        running.set(false);
        finished.countDown();
        log.warn("面试启动失败，已回滚 session={}", fresh.getSessionId());
    }

    /**
     * 收尾。
     *
     * <p>前端会重复调用：自然收尾时引擎已经自己停过一次，随后那次要还能拿到时长与可复盘判定，
     * 所以留一份最后的状态。
     */
    public InterviewState stop(boolean aborted) {
        if (!running.get()) {
            return lastFinished;
        }
        InterviewState[] holder = new InterviewState[1];
        runSync(() -> holder[0] = doStop(aborted));
        // 收尾要等落库完成，之后再拆掉循环
        if (loop != null) {
            loop.close();
            loop = null;
        }
        running.set(false);
        finished.countDown();
        return holder[0];
    }

    private InterviewState doStop(boolean aborted) {
        if (state == null) {
            return lastFinished;
        }
        if (closing) {
            return state;
        }
        closing = true;

        cancelTurnTimer();
        cancelVerbosityTimer();
        cancelSilenceTimer();
        SessionLoop.cancel(tickTimer);
        tickTimer = null;

        state.setElapsedMs(clockMs());
        String audioPath = "";
        if (voice != null) {
            rememberGain(lastStatus.autoGain());
            audioPath = Text.safe(voice.close("面试结束"));
            voice = null;
        }

        sessions.syncRecords(state);
        sessions.persistState(state);
        sessions.finish(state.getSessionId(), (int) state.getElapsedMs(), audioPath);
        sessions.setStatus(state.getSessionId(),
                aborted ? SessionStatus.ABORTED : SessionStatus.REVIEWING);

        InterviewState done = state;
        state = null;
        lastFinished = done;
        log.info("面试结束 session={} 时长={}ms 可复盘={}",
                done.getSessionId(), done.getElapsedMs(), done.isReviewable());
        return done;
    }

    /** 面试从开始到结束都挂在这个等待上，正常要等几十分钟。 */
    public void waitFinished() throws InterruptedException {
        finished.await();
    }

    private long clockMs() {
        return startedAtNanos == 0 ? 0 : (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** 记住这台机器的麦克风增益，下次开场不用再爬坡。 */
    private void rememberGain(double gain) {
        AppSettings settings = store.settings();
        if (Math.abs(gain - settings.getAudio().getLearnedGain()) < 0.15) {
            return;
        }
        try {
            settings.getAudio().setLearnedGain(gain);
            store.save(settings);
            log.info("已记住麦克风增益 {}x", Text.fixed(settings.getAudio().getLearnedGain(), 2));
        } catch (Exception e) {
            log.warn("保存麦克风增益失败: {}", e.getMessage());
        }
    }

    /** 阈值偏高会让服务端听不到说话，而已保存的旧配置不会因为改默认值而更新。 */
    private void warnAudioConfig(AppSettings settings) {
        double threshold = settings.getAudio().getVadThreshold();
        if (threshold <= 0.35) {
            return;
        }
        log.warn("人声灵敏度偏高：{}，服务端可能判定为静音", Text.fixed(threshold, 2));
        bus.emit(new AppEvent.EngineFailure(
                "人声灵敏度当前 " + Text.fixed(threshold, 2) + " 偏高，"
                        + "若面试官听不到你说话，去「设置 → 音频」调到 0.30 以下", "", false));
    }

    // ---------- VoiceSink 实现：只转投，不改状态 ----------

    @Override
    public void onState(boolean connected, String reason) {
        bus.emit(new AppEvent.RealtimeStateChanged(connected, reason));
    }

    @Override
    public void onVoiceStatus(VoiceStatus status) {
        post(() -> lastStatus = status);
    }

    @Override
    public void onCandidateSpeech(boolean speaking) {
        post(() -> {
            candidateSpeaking = speaking;
            bus.emit(new AppEvent.SpeechActivity(speaking));
            if (speaking) {
                if (pendingAnswer.isEmpty()) {
                    answerStartedMs = clockMs();
                }
                cancelTurnTimer();
                cancelSilenceTimer();
                startVerbosityTimer();
            } else {
                cancelVerbosityTimer();
            }
        });
    }

    @Override
    public void onCandidateText(String text, boolean finalText, long startedAtMs, long durationMs) {
        if (!finalText) {
            bus.emit(new AppEvent.TranscriptDelta(Speaker.CANDIDATE.value(), text));
            return;
        }
        post(() -> {
            if (state == null || Text.safe(text).strip().length() < MIN_ANSWER_CHARS) {
                return;
            }
            TurnRecord turn = state.appendTurn(Speaker.CANDIDATE, text,
                    startedAtMs > 0 ? startedAtMs : answerStartedMs, durationMs, null, false);
            pendingAnswer.add(text);
            bus.emit(new AppEvent.TranscriptCommitted(turn.getIndex(), Speaker.CANDIDATE.value(),
                    text, turn.getStartedAtMs(), turn.getDurationMs()));
            persistTurn(turn.getIndex());
            scheduleTurn();
        });
    }

    @Override
    public void onInterviewerText(String text, boolean finalText, long startedAtMs,
                                 long durationMs) {
        // 面试官的文本比它的声音早好几秒，逐字上屏会把下一题提前剧透出来。
        // 整句压到语音开口那一刻再上屏，正好可以对着听
        if (!finalText) {
            return;
        }
        post(() -> {
            if (state == null) {
                return;
            }
            QuestionRecord current = state.currentQuestion();
            TurnRecord turn = state.appendTurn(Speaker.INTERVIEWER, text, startedAtMs, durationMs,
                    current == null ? null : current.getIntent(), false);
            AppEvent line = new AppEvent.TranscriptCommitted(turn.getIndex(),
                    Speaker.INTERVIEWER.value(), text, turn.getStartedAtMs(),
                    turn.getDurationMs());
            afterVoiceOpen(() -> bus.emit(line));
            // 落库与守卫不等开口：它们与用户看到什么无关
            persistTurn(turn.getIndex());
            guardCheck(text);
        });
    }

    @Override
    public void onVoiceOpen(String cueToken, long leadMs) {
        post(() -> {
            // leadMs 是这段音频前面还排着多久，本轮真正出声要等到那时候
            voiceOpenAtNanos = System.nanoTime() + leadMs * 1_000_000L;
            PendingCue cue = cues.remove(cueToken);
            if (cue == null || cue.epoch() != cueEpoch.get()) {
                // 这一轮被打断作废了，展示负载跟着丢
                return;
            }
            afterVoiceOpen(() -> {
                voicedQuestion = cue.question();
                if (cue.event() != null) {
                    bus.emit(cue.event());
                }
            });
        });
    }

    /**
     * 等到本轮语音真正开口再执行。
     *
     * <p>期间被打断（epoch 变了）或面试已收尾就不执行——那段音频永远不会出声，跟着它上屏的
     * 东西也不该出现。
     */
    private void afterVoiceOpen(Runnable action) {
        int epoch = cueEpoch.get();
        long delayMs = Math.max(0, (voiceOpenAtNanos - System.nanoTime()) / 1_000_000);
        Runnable guarded = () -> {
            if (state != null && !closing && epoch == cueEpoch.get()) {
                action.run();
            }
        };
        if (delayMs == 0) {
            guarded.run();
            return;
        }
        loop.postDelayed(Duration.ofMillis(delayMs), guarded);
    }

    @Override
    public void onResponse(boolean active) {
        post(() -> {
            // 生成结束不等于说完，正常轮次的沉默计时由心跳里的播放状态翻转驱动
            if (active) {
                cancelTurnTimer();
                cancelSilenceTimer();
            } else if (!interviewerVoicing) {
                // 这一轮一声没出（被取消，或服务端没给音频），等不到播放结束的翻转
                startSilenceTimer();
            }
        });
    }

    @Override
    public void onBargeIn() {
        post(() -> {
            voidStagedCues();
            bus.emit(new AppEvent.InterruptionFired(false, "你打断了面试官"));
        });
    }

    @Override
    public void onError(String message, boolean fatal) {
        bus.emit(new AppEvent.EngineFailure(message, "", fatal));
        if (fatal) {
            // 致命错误的收尾不能在 loop 线程里同步做，那会自锁
            Thread killer = new Thread(() -> stop(true), "engine-abort");
            killer.setDaemon(true);
            killer.start();
        }
    }

    // ---------- 回合边界 ----------

    private void scheduleTurn() {
        cancelTurnTimer();
        long gap = store.settings().getOrchestration().getTurnGapMs();
        turnTimer = loop.postDelayed(Duration.ofMillis(gap), this::onTurnGapElapsed);
    }

    private void onTurnGapElapsed() {
        if (candidateSpeaking || closing || state == null) {
            return;
        }
        // 沉默施压：高分人设额外停顿再说话，营造压迫感
        int silence = state.getPersona().getPressure().getSilencePressure();
        if (silence >= 3) {
            long extra = 500 + (silence - 3) * 500L;
            turnTimer = loop.postDelayed(Duration.ofMillis(extra), () -> {
                if (!candidateSpeaking && !closing) {
                    requestTurn();
                }
            });
            return;
        }
        requestTurn();
    }

    private void cancelTurnTimer() {
        SessionLoop.cancel(turnTimer);
        turnTimer = null;
    }

    private void startVerbosityTimer() {
        if (state == null) {
            return;
        }
        var settings = store.settings().getOrchestration();
        // 打断倾向为 0 时直接跳过，兑现「绝不打断」的承诺
        if (state.getPersona().getPressure().getInterruptTendency() == 0) {
            return;
        }
        if (!state.canInterrupt(settings.getInterruptBudgetPerPhase())) {
            return;
        }
        cancelVerbosityTimer();
        long threshold = Policy.interruptThresholdMs(state, settings);
        verbosityTimer = loop.postDelayed(Duration.ofMillis(threshold), () -> {
            if (candidateSpeaking && !closing) {
                interruptNow("你说得太久还没落到重点");
            }
        });
    }

    private void cancelVerbosityTimer() {
        SessionLoop.cancel(verbosityTimer);
        verbosityTimer = null;
    }

    private void startSilenceTimer() {
        if (state == null || state.getPhase() == InterviewPhase.CLOSING) {
            return;
        }
        cancelSilenceTimer();
        silenceTimer = loop.postDelayed(COPILOT_SILENCE, () -> {
            if (candidateSpeaking || closing) {
                return;
            }
            if (store.settings().getFeatures().isCopilotEnabled()) {
                requestHint(true);
            }
            silenceTimer = loop.postDelayed(NUDGE_SILENCE.minus(COPILOT_SILENCE), () -> {
                if (!candidateSpeaking && !closing) {
                    nudge();
                }
            });
        });
    }

    private void cancelSilenceTimer() {
        SessionLoop.cancel(silenceTimer);
        silenceTimer = null;
    }

    private void nudge() {
        if (voice == null || lastStatus.responding()) {
            return;
        }
        try {
            voice.sendDirective(Anchor.nudgeDirective(), null);
        } catch (Exception e) {
            log.debug("轻推失败: {}", e.getMessage());
        }
    }

    /** 面试官主动插话。打断不需要新的内容决策，直接从状态拼指令，零额外延迟。 */
    private void interruptNow(String reason) {
        if (state == null || voice == null) {
            return;
        }
        QuestionRecord current = state.currentQuestion();
        String focus = current == null ? "刚才的问题" : current.getBrief();
        String directive = Anchor.directiveMessage(TurnIntent.INTERRUPT,
                "他已经说了很久还没讲到重点。打断他，要求他直接回答：" + focus);
        state.setInterruptsUsedInPhase(state.getInterruptsUsedInPhase() + 1);
        TurnRecord marked = state.lastCandidateTurn();
        if (marked != null) {
            marked.setWasInterrupted(true);
        }
        voidStagedCues();
        voice.bargeIn(directive);
        bus.emit(new AppEvent.InterruptionFired(true, reason));
        log.info("面试官主动打断：{}", reason);
    }

    // ---------- 主推进 ----------

    /**
     * 请求推进一轮。
     *
     * <p>turnRunning 起 Python 里 turn_lock 的作用：一轮还没跑完时新的请求只置位，
     * 由跑完的那一轮接着处理，绝不并发跑两轮导演。
     */
    private void requestTurn() {
        if (closing || state == null) {
            return;
        }
        if (turnRunning) {
            turnPending = true;
            return;
        }
        turnRunning = true;
        try {
            runTurn();
        } catch (InterviewerException e) {
            log.warn("回合推进失败: {}", e.getMessage());
            bus.emit(new AppEvent.EngineFailure(e.userMessage(), e.detail(), false));
            recoverTurn();
        } catch (Exception e) {
            log.error("回合推进异常", e);
            bus.emit(new AppEvent.EngineFailure("面试推进出现异常",
                    Text.safe(e.getMessage()), false));
            recoverTurn();
        } finally {
            turnRunning = false;
            if (turnPending && !closing) {
                turnPending = false;
                loop.post(this::requestTurn);
            }
        }
    }

    private void runTurn() {
        if (state == null || voice == null) {
            return;
        }
        state.setElapsedMs(clockMs());
        String answer = String.join(" ", pendingAnswer).strip();

        if (state.getPhase() == InterviewPhase.CANDIDATE_QA && !answer.isEmpty()) {
            pendingAnswer.clear();
            voice.sendDirective(Anchor.candidateQuestionDirective(answer), null);
            sessions.persistState(state);
            return;
        }

        TurnPlan plan = Policy.planTurn(state, store.settings().getOrchestration());
        DirectorDecision decision = director.decide(state, plan);
        // 决策成功才消费缓冲：导演超时或抛错时这段回答要留给下一轮，不能丢
        pendingAnswer.clear();

        DepthAction action = state.observeAnswer(decision.answerQuality());
        decision = Policy.enforceDepthAction(state, decision, action);

        for (String skill : decision.coveredSkills()) {
            state.markSkillTouched(skill);
        }
        if (!decision.dimensionDeltas().isEmpty()) {
            state.blendScores(decision.dimensionDeltas());
            bus.emit(new AppEvent.LiveScoreUpdated(new EnumMap<>(state.getLiveScores())));
        }

        if (decision.intent() == TurnIntent.CLOSE) {
            closeInterview(decision);
            return;
        }

        applyPhaseChange(decision, plan);

        String hint = "";
        if (decision.intent() == TurnIntent.STAR_PROBE) {
            hint = starHint;
        } else if (decision.isPersonality()) {
            hint = "这是一个性格/价值观问题，语气可以缓一点，但仍要问得具体。";
        }

        // 要在 openQuestion 之前取：它会把当前题换成新的
        QuestionRecord previous = state.currentQuestion();
        boolean switchedTopic = decision.intent() == TurnIntent.ASK_NEW
                && previous != null && Text.notBlank(decision.domain())
                && !decision.domain().equals(previous.getDomain());

        state.openQuestion(decision.intent(), decision.brief(), decision.targetSkill(),
                decision.domain(), decision.depth(),
                decision.chosenQuestion() == null ? null : decision.chosenQuestion().id(),
                decision.isPersonality());

        maybeReanchor("周期重锚", false);
        voice.sendDirective(
                Anchor.directiveMessage(decision.intent(), decision.brief(), hint, "",
                        switchedTopic),
                stageCue(decision.brief(), new AppEvent.DirectorDecided(decision.intent(),
                        decision.brief(), decision.targetSkill(), state.getFollowUpDepth())));

        if (!answer.isEmpty()) {
            starCheck(answer);
        }
        sessions.persistState(state);
    }

    private void applyPhaseChange(DirectorDecision decision, TurnPlan plan) {
        InterviewPhase target = Policy.resolvePhaseTransition(state);
        if (target == null && decision.shouldAdvancePhase() && !plan.mustClose()) {
            InterviewPhase next = state.nextPhase();
            target = next == state.getPhase() ? null : next;
        }
        if (target == null) {
            return;
        }
        if (target == InterviewPhase.FINISHED) {
            target = InterviewPhase.CLOSING;
        }
        String reason = state.mustClose() ? "时间到收尾线" : "本环节已完成";
        state.enterPhase(target);
        bus.emit(new AppEvent.PhaseChanged(target, reason));
        maybeReanchor("进入" + target.label(), true);
    }

    private void closeInterview(DirectorDecision decision) {
        if (state.getPhase() != InterviewPhase.CLOSING) {
            state.enterPhase(InterviewPhase.CLOSING);
            bus.emit(new AppEvent.PhaseChanged(InterviewPhase.CLOSING, "进入收尾"));
        }
        state.openQuestion(TurnIntent.CLOSE, decision.brief(), "", "", 1, null, false);
        voice.sendDirective(Anchor.directiveMessage(TurnIntent.CLOSE, decision.brief()), null);
        sessions.persistState(state);
        waitForClosingSpeech();
    }

    /** 等收尾话说完再断链，避免最后一句被截断。 */
    private void waitForClosingSpeech() {
        AtomicInteger ticks = new AtomicInteger();
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        holder[0] = loop.postPeriodic(Duration.ofMillis(250), () -> {
            if (closing) {
                SessionLoop.cancel(holder[0]);
                return;
            }
            boolean done = !lastStatus.responding() && lastStatus.pendingMs() <= 0;
            if (done || ticks.incrementAndGet() > 120) {
                SessionLoop.cancel(holder[0]);
                Thread stopper = new Thread(() -> stop(false), "engine-finish");
                stopper.setDaemon(true);
                stopper.start();
            }
        });
    }

    /**
     * 一轮失败不能让面试卡死，重锚后让面试官把话语权交回候选人。
     *
     * <p>保留候选人已说的内容：因为导演超时就把用户刚讲的一大段丢掉，体验上等于没听见。
     */
    private void recoverTurn() {
        if (state == null || voice == null) {
            return;
        }
        String answer = String.join(" ", pendingAnswer).strip();
        pendingAnswer.clear();
        try {
            maybeReanchor("异常恢复", true);
            String brief = answer.isEmpty()
                    ? "用一句极短的话让候选人继续把刚才的回答说完"
                    : "候选人刚才回答了：" + Text.cut(answer, 150) + "。用一句话确认你听到了，再问下一个问题";
            voice.sendDirective(Anchor.directiveMessage(TurnIntent.ACKNOWLEDGE, brief), null);
        } catch (Exception e) {
            log.warn("异常恢复失败: {}", e.getMessage());
        }
    }

    // ---------- 重锚与守卫 ----------

    private void maybeReanchor(String trigger, boolean force) {
        if (voice == null || state == null) {
            return;
        }
        int every = store.settings().getOrchestration().getReanchorEveryTurns();
        if (!force && !state.needsReanchor(every)) {
            return;
        }
        voice.reanchor(Anchor.buildInstructions(state));
        state.noteReanchor();
        bus.emit(new AppEvent.ReanchorPerformed(state.getTurnIndex(), trigger));
        log.info("已重锚人格 turn={} trigger={}", state.getTurnIndex(), trigger);
    }

    /**
     * 守卫检查。
     *
     * <p>放到别的线程上跑：它要调模型，几百毫秒到两秒，不能占着 loop 线程——那会让心跳、
     * 转写、打断判定全部排在后面。
     */
    private void guardCheck(String spoken) {
        int timeout = store.settings().getOrchestration().getGuardTimeoutMs();
        var persona = state.getPersona();
        Thread worker = new Thread(() -> {
            Guard.Verdict verdict = guard.inspect(spoken, persona, timeout);
            if (!verdict.violated()) {
                return;
            }
            post(() -> applyDrift(verdict));
        }, "engine-guard");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyDrift(Guard.Verdict verdict) {
        if (state == null || voice == null) {
            return;
        }
        state.registerDrift(verdict.kind());
        boolean repaired = false;
        try {
            voice.cancelCurrentResponse();
            voidStagedCues();
            voice.reanchor(Anchor.buildInstructions(state));
            voice.sendDirective(Guard.repairDirective(verdict, state.getPersona()), null);
            state.noteReanchor();
            repaired = true;
        } catch (Exception e) {
            log.warn("漂移修复失败: {}", e.getMessage());
        }
        bus.emit(new AppEvent.DriftDetected(verdict.kind(), verdict.excerpt(), repaired));
        log.warn("人格漂移已处理 kind={} repaired={}", verdict.kind().value(), repaired);
    }

    private void starCheck(String answer) {
        if (state == null || !state.getStar().isBehavioral()) {
            return;
        }
        QuestionRecord current = state.currentQuestion();
        String question = current == null ? ""
                : (Text.notBlank(current.getSpokenText()) ? current.getSpokenText()
                : current.getBrief());
        Thread worker = new Thread(() -> {
            StarAnalyst.Verdict verdict = star.analyze(question, answer);
            if (verdict == null) {
                return;
            }
            post(() -> {
                if (state == null) {
                    return;
                }
                state.getStar().setPresent(verdict.present());
                starHint = verdict.probeHint();
                bus.emit(new AppEvent.StarProgress(Set.copyOf(verdict.present()),
                        Set.copyOf(state.getStar().missing()), true));
            });
        }, "engine-star");
        worker.setDaemon(true);
        worker.start();
    }

    // ---------- 用户交互 ----------

    /** auto=true 时由沉默监视器触发，用户并没点提词，失败就静默跳过。 */
    public void requestHint(boolean auto) {
        if (!running.get()) {
            return;
        }
        post(() -> {
            if (state == null) {
                return;
            }
            // 用已经问出口的那道题：导演可能已经推进到下一题，而候选人还在答这一题
            String question = voicedQuestion;
            String partial = String.join(" ", pendingAnswer).strip();
            String digest = state.getResumeDigest();
            Thread worker = new Thread(() -> {
                Copilot.Hint hint = copilot.hint(question, partial, digest);
                if (hint.isEmpty()) {
                    if (!auto) {
                        String model = store.settings().chatModel(Providers.ROLE_ASSIST);
                        bus.emit(new AppEvent.EngineFailure("提词器（" + model
                                + "）没能及时返回，可能网络慢或模型繁忙，稍后再试", "", false));
                    }
                    return;
                }
                bus.emit(new AppEvent.CopilotHint(hint.keywords(), hint.outline(),
                        hint.caution()));
            }, "engine-copilot");
            worker.setDaemon(true);
            worker.start();
        });
    }

    public void submitCode(String language, String source) {
        if (!running.get()) {
            return;
        }
        post(() -> {
            if (state == null || voice == null) {
                return;
            }
            state.setCodeLanguage(language);
            state.setCodeSnapshot(source);
            QuestionRecord current = state.currentQuestion();
            String problem = current == null ? ""
                    : (Text.notBlank(current.getSpokenText()) ? current.getSpokenText()
                    : current.getBrief());
            Thread worker = new Thread(() -> {
                CodeExaminer.Probe probe = code.probe(language, source, problem);
                post(() -> applyCodeProbe(probe));
            }, "engine-code");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void applyCodeProbe(CodeExaminer.Probe probe) {
        if (state == null || voice == null) {
            return;
        }
        if (probe == null) {
            bus.emit(new AppEvent.EngineFailure("代码分析超时，面试官这次先不追问", "", false));
            return;
        }
        QuestionRecord current = state.currentQuestion();
        if (current != null) {
            current.setQuality(probe.quality());
        }
        Map<ScoreDimension, Double> delta = new EnumMap<>(ScoreDimension.class);
        delta.put(ScoreDimension.CODING, probe.quality() * 100);
        state.blendScores(delta);
        bus.emit(new AppEvent.LiveScoreUpdated(new EnumMap<>(state.getLiveScores())));
        voice.sendDirective(Anchor.codeReviewDirective(probe.probe(), probe.complexity(),
                probe.issues()), null);
        sessions.persistState(state);
    }

    public void interruptInterviewer() {
        post(() -> {
            if (voice == null) {
                return;
            }
            voice.cancelCurrentResponse();
            voidStagedCues();
            bus.emit(new AppEvent.InterruptionFired(false, "你打断了面试官"));
        });
    }

    public void finishEarly() {
        post(() -> {
            if (state == null || voice == null) {
                return;
            }
            state.enterPhase(InterviewPhase.CLOSING);
            bus.emit(new AppEvent.PhaseChanged(InterviewPhase.CLOSING, "提前结束"));
            maybeReanchor("提前收尾", true);
            voice.sendDirective(Anchor.directiveMessage(TurnIntent.CLOSE,
                    "时间关系，今天先聊到这里，向候选人说明后续流程"), null);
            waitForClosingSpeech();
        });
    }

    public void setMuted(boolean muted) {
        post(() -> {
            if (voice != null) {
                voice.setMuted(muted);
            }
        });
    }

    // ---------- 心跳 ----------

    private void onTick() {
        if (state == null) {
            return;
        }
        state.setElapsedMs(clockMs());
        bus.emit(new AppEvent.ElapsedTick(state.getElapsedMs(), state.remainingMs()));
        bus.emit(new AppEvent.AudioLevel(lastStatus.candidateLevel(), lastStatus.voiceLevel()));

        boolean voicing = lastStatus.voicing();
        if (voicing != interviewerVoicing) {
            interviewerVoicing = voicing;
            bus.emit(new AppEvent.InterviewerSpeaking(voicing));
            // 缓冲里压着六到十秒，从生成完起算沉默会在用户还在听题时就弹提词、还催一句。
            // 要从真的说完那一刻算
            if (voicing) {
                cancelSilenceTimer();
            } else {
                startSilenceTimer();
            }
        }

        // 只触发一次：心跳每 100ms 一跳，反复置位会排出几十次多余的导演调用
        if (!closeTriggered && state.mustClose()
                && state.getPhase() != InterviewPhase.CLOSING) {
            closeTriggered = true;
            requestTurn();
        }
    }

    // ---------- 辅助 ----------

    private String stageCue(String question, AppEvent.DirectorDecided event) {
        String token = "cue-" + cueEpoch.get() + "-" + System.nanoTime();
        cues.put(token, new PendingCue(question, event, cueEpoch.get()));
        return token;
    }

    private void voidStagedCues() {
        cueEpoch.incrementAndGet();
        cues.clear();
    }

    private void persistTurn(int turnIndex) {
        TurnRecord turn = state.getTurns().stream()
                .filter(t -> t.getIndex() == turnIndex).findFirst().orElse(null);
        if (turn == null) {
            return;
        }
        int sessionId = state.getSessionId();
        QuestionRecord question = state.currentQuestion();
        // 落库是 IO，不占 loop 线程
        Thread worker = new Thread(() -> {
            try {
                sessions.upsertTurn(sessionId, turn);
                if (question != null) {
                    sessions.upsertQuestion(sessionId, question);
                }
            } catch (Exception e) {
                log.warn("落库单轮失败: {}", e.getMessage());
            }
        }, "engine-persist");
        worker.setDaemon(true);
        worker.start();
    }

    private void post(Runnable action) {
        SessionLoop current = loop;
        if (current != null) {
            current.post(action);
        }
    }

    /** 在循环线程上同步跑完一个动作。start / stop 需要拿到结果才能返回。 */
    private void runSync(Runnable action) {
        SessionLoop current = loop;
        if (current == null) {
            return;
        }
        if (current.inLoop()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        RuntimeException[] error = new RuntimeException[1];
        current.post(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                error[0] = e;
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (error[0] != null) {
            throw error[0];
        }
    }
}
