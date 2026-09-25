package com.interviewer.llm;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型调用的工程量参数。
 *
 * <p>不进用户配置：超时与限流是实现细节，调错了只会让面试卡住或白花钱，不该暴露给界面。
 */
@Component
@ConfigurationProperties(prefix = "interviewer.llm")
@Getter
@Setter
public class LlmSettings {

    /**
     * 各角色的读超时。
     *
     * <p>差异很大：导演在语音链路上，超过二十秒这一轮就废了；复盘是离线长任务，
     * 四分钟都算正常。
     */
    private Map<String, Duration> timeout = new HashMap<>();

    /** 各角色每秒允许的调用次数。用户的额度是真金白银，编排出 bug 时要拦得住。 */
    private Map<String, Integer> qps = new HashMap<>();

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_QPS = 4;

    public Duration timeoutOf(String role) {
        return timeout.getOrDefault(role, DEFAULT_TIMEOUT);
    }

    public int qpsOf(String role) {
        return qps.getOrDefault(role, DEFAULT_QPS);
    }
}
