package com.interviewer.rpc;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 跨域与事件通道的注册。
 *
 * <p>桌面前端跑在 WebView 里，向本机端口发请求属于跨域，必须显式放行来源。开发态是 Vite
 * 的地址，打包态由 Tauri 提供。真正的门禁是 token（见 {@link TokenGuard}），这里只是让
 * 浏览器的同源策略不要提前掐断请求。
 */
@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {

    static final String[] ALLOWED_ORIGINS = {
            "http://localhost:1420",
            "http://127.0.0.1:1420",
            "http://tauri.localhost",
            "https://tauri.localhost",
            "tauri://localhost",
    };

    private final EventsHandler events;

    public WebConfig(EventsHandler events) {
        this.events = events;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(ALLOWED_ORIGINS)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders(TokenGuard.allowedHeaders())
                .maxAge(600);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(events, "/events").setAllowedOrigins(ALLOWED_ORIGINS);
    }
}
