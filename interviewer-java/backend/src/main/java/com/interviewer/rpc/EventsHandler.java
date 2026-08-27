package com.interviewer.rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 事件通道。
 *
 * <p>只出不进：前端不通过它发指令，收到什么都忽略，只用它感知断连。握手同样要过
 * {@link TokenGuard}（token 在查询串里）。
 */
@Component
public class EventsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventsHandler.class);

    private final EventHub hub;

    public EventsHandler(EventHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        hub.attach(session);
        log.info("事件订阅端已连接，当前 {} 个", hub.clientCount());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.detach(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        hub.detach(session);
    }
}
