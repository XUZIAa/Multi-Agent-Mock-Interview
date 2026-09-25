package com.interviewer.rpc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 本机回环也不等于可信：同机任何进程都能连上来。
 *
 * <p>面试引擎能花钱调模型、能读写简历、能取用密钥，因此每个请求都必须带启动时生成的
 * 一次性 token。
 */
@Component
@Order(0)
public class TokenGuard extends OncePerRequestFilter {

    static final String HEADER = "X-Interviewer-Token";

    private static final Set<String> OPEN_PATHS = Set.of("/v3/api-docs");

    private final byte[] token;

    public TokenGuard(@Value("${interviewer.token:}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        // 预检请求不带自定义头，拦下它等于让所有跨域调用直接失败
        if (HttpMethod.OPTIONS.matches(request.getMethod())
                || OPEN_PATHS.contains(path) || path.startsWith("/v3/api-docs/")) {
            chain.doFilter(request, response);
            return;
        }
        String supplied = request.getHeader(HEADER);
        if (supplied == null) {
            supplied = request.getParameter("token");
        }
        if (!matches(supplied)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"detail\":\"token 不正确\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 定长比较，避免用比较耗时反推 token。 */
    private boolean matches(String supplied) {
        if (supplied == null || supplied.isEmpty() || token.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), token);
    }

    /** CORS 预检的响应头由框架补齐，这里只声明放行的自定义头名。 */
    static String[] allowedHeaders() {
        return new String[] {HEADER, HttpHeaders.CONTENT_TYPE};
    }
}
