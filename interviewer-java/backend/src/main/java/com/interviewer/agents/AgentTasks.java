package com.interviewer.agents;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 调用的超时与并发。
 *
 * <p>超时必须真的中断底层请求：守卫的语义检查只给 2.5 秒，而 HTTP 读超时是 8 秒。
 * 只等不取消的话，超时之后那个线程还要占五六秒——一场面试几十轮，线程就堆起来了。
 * 所以用 Future.cancel(true) 打断，JDK 的 HttpClient 会以 InterruptedIOException 退出。
 */
@Component
public class AgentTasks {

    private static final Logger log = LoggerFactory.getLogger(AgentTasks.class);

    private final ExecutorService pool = new ThreadPoolExecutor(
            0, 32, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            newFactory(), new ThreadPoolExecutor.CallerRunsPolicy());

    private static java.util.concurrent.ThreadFactory newFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "agent-task");
            t.setDaemon(true);
            return t;
        };
    }

    /** 超时或异常时返回兜底值。守卫、STAR、提词器都用它——它们失败不该中断面试。 */
    public <T> T withTimeout(Duration timeout, Supplier<T> action, T onFailure, String label) {
        Future<T> future = pool.submit(action::get);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.info("{}超时（{}ms），本轮跳过", label, timeout.toMillis());
            return onFailure;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return onFailure;
        } catch (Exception e) {
            log.warn("{}调用失败: {}", label, rootMessage(e));
            return onFailure;
        }
    }

    /**
     * 全部跑完再收集，单个失败不影响其余。
     *
     * <p>复盘的四个子任务用它：评分、批注、重构、错题彼此独立，一个挂了不该让整份报告没有。
     */
    public <T> List<Settled<T>> allSettled(List<Supplier<T>> tasks, Duration timeout) {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        tasks.forEach(task -> futures.add(pool.submit(task::get)));

        List<Settled<T>> out = new ArrayList<>(tasks.size());
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Future<T> future : futures) {
            long left = Math.max(0, deadline - System.nanoTime());
            try {
                out.add(new Settled<>(future.get(left, TimeUnit.NANOSECONDS), null));
            } catch (TimeoutException e) {
                future.cancel(true);
                out.add(new Settled<>(null, new TimeoutException("子任务超时")));
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                out.add(new Settled<>(null, e));
            } catch (Exception e) {
                out.add(new Settled<>(null, e.getCause() == null ? e : e.getCause()));
            }
        }
        return out;
    }

    /** 单个子任务的结局。value 与 error 恰有一个非空。 */
    public record Settled<T>(T value, Throwable error) {

        public boolean ok() {
            return error == null;
        }

        /** 失败时取兜底值，并把原因交给调用方决定是否上报。 */
        public T orElse(T fallback) {
            return error == null ? value : fallback;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
