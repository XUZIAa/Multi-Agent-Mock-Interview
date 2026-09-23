package com.interviewer.core.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 模型能力判定。
 *
 * <p>判错的代价是三处配置一起错：输出配额下限、能不能下发 response_format、能不能调
 * temperature。推理模型被当成普通模型，思维链会把配额吃光、正文返回空。
 */
class ModelTraitsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "deepseek-reasoner",
            "qwen3-235b-a22b-thinking-2507",
            "qwq-32b",
            "o1",
            "o1-mini",
            "o3-mini",
            "DeepSeek-R1",
            "hunyuan-t1-latest-reasoning",
    })
    void reasoningModelsAreRecognised(String model) {
        assertTrue(ModelTraits.of(model).reasoning(), model + " 应当被判为推理模型");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "deepseek-chat",
            "glm-4-flash",
            "qwen-plus",
            "kimi-k2-turbo-preview",
            "gpt-4o",
            "moonshot-v1-128k",
    })
    void plainModelsStayPlain(String model) {
        assertFalse(ModelTraits.of(model).reasoning(), model + " 不该被判为推理模型");
    }

    /** model-o1x 这类名字不能因为含 o1 就误判。 */
    @Test
    void doesNotMatchInsideLongerTokens() {
        assertFalse(ModelTraits.of("model-o1x").reasoning());
        assertFalse(ModelTraits.of("gr1d-large").reasoning());
    }

    /**
     * 记录当前的识别盲区。
     *
     * <p>这些模型默认开思考，但名字里没有 reasoner/thinking 字样，按名字判定认不出来。
     * 用户会踩到「正文为空」并收到一条让他「改用非推理模型」的提示——而系统已经认为
     * 它是非推理模型了，照做也没用（GitHub issue #2）。
     *
     * <p>断言写的是「现状」而不是「期望」：改进方案定下来之后，这个用例要跟着翻过来。
     */
    @ParameterizedTest
    @ValueSource(strings = {"glm-5.3-flash", "glm-4.6", "doubao-seed-1-6-250615", "qwen3-max"})
    void knownBlindSpotsAreStillMisjudged(String model) {
        assertFalse(ModelTraits.of(model).reasoning(),
                model + " 目前按名字认不出是推理模型——这是 issue #2 的根因");
    }
}
