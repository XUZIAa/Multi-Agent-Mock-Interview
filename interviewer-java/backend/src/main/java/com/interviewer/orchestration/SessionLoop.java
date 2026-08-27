package com.interviewer.orchestration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一场面试的单线程事件循环。
 *
 * <p>这是整个移植里最关键的一处设计。Python 版的正确性建立在「单事件循环、无抢占」上：
 * 状态被转写回调、每 100ms 的心跳、推进循环、守卫的后台任务同时读写，却全程没有一把锁——
 * 因为 asyncio 里它们不可能真并发。
 *
 * <p>Java 侧的回调来自三类真线程：WebSocket 读线程、语音 sidecar 的推送线程、定时调度线程。
 * 所以这里显式复刻那个前提：每场面试独占一个单线程执行器，所有回调只做 {@link #post}，
 * 真正的状态变更全部排到这一个线程上。没有它就得给几十个字段逐个加锁，
 * 而漏一个的后果是三十分钟长会话里偶发的竞态——最难查的那种。
 *
 * <p>调用方因此可以像写单线程代码一样写编排逻辑。
 */
public class SessionLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLoop.class);

    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private final Thread owner;

    public SessionLoop(int sessionId) {
        Thread[] holder = new Thread[1];
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "interview-loop-" + sessionId);
            t.setDaemon(true);
            holder[0] = t;
            return t;
        });
        // 先跑一个空任务把线程创建出来，之后 inLoop 才能判断
        executor.execute(() -> {
        });
        this.owner = holder[0];
    }

    /** 排一个动作到循环线程上。已关闭时静默丢弃——收尾之后的回调不该再改状态。 */
    public void post(Runnable action) {
        if (closed.get()) {
            return;
        }
        try {
            executor.execute(() -> run(action));
        } catch (RejectedExecutionException e) {
            // 与 closed 的检查之间有窗口，正常收尾路径，不必告警
            log.debug("循环已关闭，丢弃一个动作");
        }
    }

    /** 延时执行。返回句柄以便取消——各类定时器都靠取消来重置。 */
    public ScheduledFuture<?> postDelayed(Duration delay, Runnable action) {
        if (closed.get()) {
            return null;
        }
        try {
            ScheduledFuture<?> future = executor.schedule(
                    () -> run(action), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
            timers.add(future);
            return future;
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    /** 固定周期执行。心跳用它。 */
    public ScheduledFuture<?> postPeriodic(Duration period, Runnable action) {
        if (closed.get()) {
            return null;
        }
        try {
            long ms = Math.max(1, period.toMillis());
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                    () -> run(action), ms, ms, TimeUnit.MILLISECONDS);
            timers.add(future);
            return future;
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    /** 当前是否已在循环线程上。断言用，帮助在开发期发现越线的直接调用。 */
    public boolean inLoop() {
        return Thread.currentThread() == owner;
    }

    /**
     * 一个动作抛异常不能掐掉整场面试。
     *
     * <p>循环线程死了就再没有任何东西能推进面试，而单线程执行器不会自动重建它。
     */
    private void run(Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            log.error("循环内动作异常", e);
        }
    }

    public static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        timers.forEach(t -> t.cancel(false));
        timers.clear();
        executor.shutdownNow();
    }
}
