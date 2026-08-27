package com.interviewer.core.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 进程内同步事件总线。
 *
 * <p>emit 在调用者线程上跑完所有处理器，且绝不把异常抛回去——它的调用方是编排引擎
 * 的单线程循环，一个订阅者出问题不能连带掐掉面试。
 *
 * <p>因此订阅者自己有义务不阻塞：接口层的事件桥收到就丢进有界队列立刻返回，
 * 一次网络抖动才不会把编排拖住。
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<? extends AppEvent>, List<Consumer<? extends AppEvent>>> subs =
            new ConcurrentHashMap<>();

    private final List<Consumer<AppEvent>> globals = new CopyOnWriteArrayList<>();

    /** 返回退订动作。同一个 handler 订阅两次时，退订只摘掉一个。 */
    public <E extends AppEvent> Runnable subscribe(Class<E> type, Consumer<E> handler) {
        List<Consumer<? extends AppEvent>> pool =
                subs.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        pool.add(handler);
        return () -> pool.remove(handler);
    }

    /**
     * 订阅全部事件。
     *
     * <p>接口层的事件桥要转发每一类事件，少一个前端就瞎一块。按类逐个订阅得维护一份
     * 清单，新增事件时漏登记也不会报错；这里让总线自己兜住，新事件天然就在里面。
     */
    public Runnable subscribeAll(Consumer<AppEvent> handler) {
        globals.add(handler);
        return () -> globals.remove(handler);
    }

    @SuppressWarnings("unchecked")
    public void emit(AppEvent event) {
        List<Consumer<? extends AppEvent>> pool = subs.get(event.getClass());
        if (pool != null) {
            for (Consumer<? extends AppEvent> raw : pool) {
                dispatch((Consumer<AppEvent>) raw, event);
            }
        }
        for (Consumer<AppEvent> handler : globals) {
            dispatch(handler, event);
        }
    }

    private void dispatch(Consumer<AppEvent> handler, AppEvent event) {
        try {
            handler.accept(event);
        } catch (Exception e) {
            log.error("事件处理器异常: {}", event.getClass().getSimpleName(), e);
        }
    }

    public void clear() {
        subs.clear();
        globals.clear();
    }
}
