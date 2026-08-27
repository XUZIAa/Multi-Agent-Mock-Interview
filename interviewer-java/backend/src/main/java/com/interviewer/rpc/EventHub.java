package com.interviewer.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.event.AppEvent;
import com.interviewer.core.event.EventBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 把后端事件总线桥到 WebSocket。
 *
 * <p>事件在编排引擎的循环线程上同步发出，而网络写是阻塞的，中间必须有队列；否则一次网络
 * 抖动就会把编排拖住。队列有界并丢最旧的一条：卡住编排比丢一帧动画数据严重得多。
 *
 * <p>序列化放在发出方线程上做（与 Python 版一致）。事件里带的集合有可能在之后被改写，
 * 挪到刷新线程再序列化就会读到未来的状态。
 */
@Component
public class EventHub {

    private static final Logger log = LoggerFactory.getLogger(EventHub.class);

    /** 高频事件：心跳每 100ms 一跳，堆起来会把 WS 打满，只保留最新一份。 */
    private static final Set<String> COALESCED = Set.of("audio_level", "elapsed_tick");

    private static final long FLUSH_INTERVAL_MS = 50;
    private static final int QUEUE_LIMIT = 256;

    /** 全部事件名，供 /info 回报，前端按这些名字分派。 */
    public static final List<String> EVENT_NAMES = eventNames();

    private final EventBus bus;
    private final ObjectMapper mapper;

    private final Set<WebSocketSession> clients = new CopyOnWriteArraySet<>();
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private final Map<String, String> latest = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();

    private ScheduledExecutorService pump;
    private Runnable unsubscribe;

    public EventHub(EventBus bus, ObjectMapper mapper) {
        this.bus = bus;
        this.mapper = mapper;
    }

    private static List<String> eventNames() {
        List<String> names = new ArrayList<>();
        for (Class<?> type : AppEvent.class.getPermittedSubclasses()) {
            @SuppressWarnings("unchecked")
            Class<? extends AppEvent> event = (Class<? extends AppEvent>) type;
            names.add(AppEvent.nameOf(event));
        }
        return List.copyOf(names);
    }

    @PostConstruct
    void start() {
        unsubscribe = bus.subscribeAll(this::onEvent);
        pump = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rpc-event-pump");
            thread.setDaemon(true);
            return thread;
        });
        pump.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        log.info("事件桥已启动，转发 {} 类事件", EVENT_NAMES.size());
    }

    @PreDestroy
    void stop() {
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
        if (pump != null) {
            pump.shutdownNow();
            pump = null;
        }
        for (WebSocketSession session : clients) {
            try {
                session.close();
            } catch (IOException ignored) {
                // 关停路径上不追加噪音
            }
        }
        clients.clear();
    }

    // ---------- 连接管理 ----------

    void attach(WebSocketSession session) {
        clients.add(session);
    }

    void detach(WebSocketSession session) {
        clients.remove(session);
    }

    public int clientCount() {
        return clients.size();
    }

    public long droppedCount() {
        return dropped.get();
    }

    // ---------- 推送 ----------

    private void onEvent(AppEvent event) {
        publish(event.eventName(), event);
    }

    /** 从任意上下文投递一条事件。同步返回，绝不阻塞调用方。 */
    public void publish(String name, Object data) {
        String frame;
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("event", name);
            envelope.put("data", data);
            frame = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("事件序列化失败 {}: {}", name, e.getMessage());
            return;
        }
        if (COALESCED.contains(name)) {
            latest.put(name, frame);
            return;
        }
        while (!queue.offer(frame)) {
            // 丢最旧的一条，保住新事件
            queue.poll();
            dropped.incrementAndGet();
        }
    }

    private void flush() {
        List<String> frames = new ArrayList<>();
        queue.drainTo(frames);
        for (String name : COALESCED) {
            String frame = latest.remove(name);
            if (frame != null) {
                frames.add(frame);
            }
        }
        if (frames.isEmpty() || clients.isEmpty()) {
            return;
        }
        List<WebSocketSession> dead = new ArrayList<>();
        for (WebSocketSession session : clients) {
            try {
                for (String frame : frames) {
                    session.sendMessage(new TextMessage(frame));
                }
            } catch (Exception e) {
                dead.add(session);
            }
        }
        if (!dead.isEmpty()) {
            dead.forEach(clients::remove);
            log.info("移除 {} 个已断开的事件订阅端", dead.size());
        }
    }
}
