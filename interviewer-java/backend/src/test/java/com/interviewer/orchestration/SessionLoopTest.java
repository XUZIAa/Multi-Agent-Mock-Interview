package com.interviewer.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 单线程事件循环的不变量。
 *
 * <p>整个编排层的正确性建立在「所有状态变更都发生在同一个线程上」这个前提之上——
 * Python 版靠 asyncio 免费获得，这里必须自己保证。这些断言就是那个前提的证明。
 */
class SessionLoopTest {

    @Test
    void allActionsRunOnOneThread() throws Exception {
        try (SessionLoop loop = new SessionLoop(1)) {
            int total = 500;
            CountDownLatch done = new CountDownLatch(total);
            List<String> threads = new ArrayList<>();

            // 从多个线程并发投递，模拟语音回调、心跳、推进循环同时来
            List<Thread> senders = new ArrayList<>();
            for (int t = 0; t < 5; t++) {
                Thread sender = new Thread(() -> {
                    for (int i = 0; i < 100; i++) {
                        loop.post(() -> {
                            synchronized (threads) {
                                threads.add(Thread.currentThread().getName());
                            }
                            done.countDown();
                        });
                    }
                });
                senders.add(sender);
                sender.start();
            }
            for (Thread sender : senders) {
                sender.join();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "动作未在期限内跑完");

            assertEquals(total, threads.size());
            assertEquals(1, threads.stream().distinct().count(),
                    "所有动作必须落在同一个线程上，否则状态字段就需要加锁");
            assertTrue(threads.get(0).startsWith("interview-loop-"));
        }
    }

    @Test
    void mutationsNeedNoLockBecauseOfSerialExecution() throws Exception {
        try (SessionLoop loop = new SessionLoop(2)) {
            // 刻意用非线程安全的裸字段累加：串行执行下它就是安全的
            int[] counter = {0};
            int total = 2000;
            CountDownLatch done = new CountDownLatch(total);
            for (int i = 0; i < total; i++) {
                loop.post(() -> {
                    counter[0]++;
                    done.countDown();
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(total, counter[0], "串行执行下裸字段累加不该丢数");
        }
    }

    @Test
    void actionExceptionDoesNotKillLoop() throws Exception {
        try (SessionLoop loop = new SessionLoop(3)) {
            CountDownLatch after = new CountDownLatch(1);
            loop.post(() -> {
                throw new IllegalStateException("故意炸一次");
            });
            loop.post(after::countDown);
            // 循环线程死了就再没有东西能推进面试，而单线程执行器不会自动重建它
            assertTrue(after.await(5, TimeUnit.SECONDS), "一个动作抛异常不能掐掉整个循环");
        }
    }

    @Test
    void cancelledTimerDoesNotFire() throws Exception {
        try (SessionLoop loop = new SessionLoop(4)) {
            AtomicInteger fired = new AtomicInteger();
            var timer = loop.postDelayed(Duration.ofMillis(200), fired::incrementAndGet);
            SessionLoop.cancel(timer);
            Thread.sleep(400);
            assertEquals(0, fired.get(), "取消后的定时器不该触发——各类定时器都靠取消来重置");
        }
    }

    @Test
    void closedLoopSilentlyDropsActions() throws Exception {
        SessionLoop loop = new SessionLoop(5);
        AtomicInteger fired = new AtomicInteger();
        loop.close();
        // 收尾之后语音侧的回调还会来几条，那些不该再改状态，更不该抛异常
        loop.post(fired::incrementAndGet);
        assertEquals(null, loop.postDelayed(Duration.ofMillis(10), fired::incrementAndGet));
        Thread.sleep(100);
        assertEquals(0, fired.get(), "关闭后的动作应被静默丢弃");
    }

    @Test
    void inLoopDistinguishesCallerThread() throws Exception {
        try (SessionLoop loop = new SessionLoop(6)) {
            assertFalse(loop.inLoop(), "测试线程不是循环线程");
            AtomicReference<Boolean> inside = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            loop.post(() -> {
                inside.set(loop.inLoop());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(inside.get(), "循环内部应当识别出自己");
        }
    }
}
