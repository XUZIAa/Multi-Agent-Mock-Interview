package com.interviewer.rpc;

import com.interviewer.core.Text;
import com.interviewer.core.error.InterviewerException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 接口层的统一异常出口。
 *
 * <p>业务异常自带给用户看的话术，落成 {@code kind / user_message / detail} 三段；前端据此
 * 直接展示，不必解析堆栈。其余异常只给 {@code detail} 一段。
 */
@RestControllerAdvice
public class ErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorAdvice.class);

    @ExceptionHandler(InterviewerException.class)
    public ResponseEntity<Map<String, Object>> onBusiness(InterviewerException exc) {
        log.warn("接口返回业务错误 {}: {}", exc.getClass().getSimpleName(),
                Text.notBlank(exc.detail()) ? exc.detail() : exc.userMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", exc.getClass().getSimpleName());
        body.put("user_message", exc.userMessage());
        body.put("detail", exc.detail());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException exc) {
        return ResponseEntity.status(exc.getStatusCode())
                .body(Map.of("detail", Text.safe(exc.getReason())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onInvalid(MethodArgumentNotValidException exc) {
        String detail = exc.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .collect(Collectors.joining("；"));
        log.warn("请求体不合法: {}", detail);
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("detail", "请求参数有误：" + detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> onViolation(ConstraintViolationException exc) {
        String detail = exc.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("；"));
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("detail", "请求参数有误：" + detail));
    }

    /**
     * 兜底。
     *
     * <p>本机单用户场景下把原因如实给出去：用户看到「未知错误」既不能自查也没法反馈，
     * 而这里没有对外暴露面。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception exc) {
        log.error("接口出现未预期异常", exc);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", exc.getClass().getSimpleName() + ": "
                        + Text.cut(Text.safe(exc.getMessage()), 300)));
    }
}
