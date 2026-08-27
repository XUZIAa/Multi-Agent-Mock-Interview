package com.interviewer.llm;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.interviewer.core.error.RateLimitedException;
import com.interviewer.core.provider.Providers;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型调用的本机限流。
 *
 * <p>这是 Spring Cloud Alibaba 在这个项目里唯一有落点的组件：不做服务发现、不做分布式
 * 事务，只做一件事——把编排层可能的连环调用拦在本机，别让用户的额度被 bug 烧掉。
 * 不连控制台、不需要 Nacos，规则在启动时写死。
 *
 * <p>触发限流不做静默丢弃：直接报错，让调用方与用户都知道被拦了。
 */
@Component
public class LlmRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LlmRateLimiter.class);
    private static final String PREFIX = "llm:";

    private final LlmSettings settings;

    public LlmRateLimiter(LlmSettings settings) {
        this.settings = settings;
    }

    @PostConstruct
    void registerRules() {
        List<FlowRule> rules = new ArrayList<>();
        for (String role : Providers.ALL_ROLES) {
            FlowRule rule = new FlowRule();
            rule.setResource(PREFIX + role);
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(settings.qpsOf(role));
            // 匀速排队：宁可让调用等一下，也不要直接失败。等不到才报错
            rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER);
            rule.setMaxQueueingTimeMs(2000);
            rules.add(rule);
        }
        FlowRuleManager.loadRules(rules);
        log.info("已注册模型限流规则 {} 条", rules.size());
    }

    /** 在限流闸门内执行。被拦住时抛出业务异常，不静默跳过。 */
    public <T> T guard(String role, Supplier<T> action) {
        Entry entry = null;
        try {
            entry = SphU.entry(PREFIX + role);
            return action.get();
        } catch (BlockException e) {
            throw new RateLimitedException("本机限流拦截 role=" + role, e);
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }
}
