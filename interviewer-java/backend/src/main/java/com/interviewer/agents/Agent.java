package com.interviewer.agents;

import com.interviewer.llm.LlmClient;
import com.interviewer.llm.LlmRouter;

/**
 * 所有 Agent 的共同外壳。角色决定它用哪个模型。
 *
 * <p>每次都从路由取客户端而不是缓存：用户在设置里换了模型绑定后，下一轮就该生效。
 */
public abstract class Agent {

    protected final LlmRouter router;

    protected Agent(LlmRouter router) {
        this.router = router;
    }

    protected abstract String role();

    protected LlmClient client() {
        return router.client(role());
    }
}
