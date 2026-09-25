package com.interviewer.core.provider;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 模型能力差异。
 *
 * <p>同一供应商下不同模型能力可能完全不同（deepseek-chat 与 deepseek-reasoner），
 * 所以能力必须按模型名判定，不能挂在供应商上。
 *
 * @param reasoning        是否推理模型
 * @param jsonObject       能否可靠使用 response_format=json_object
 * @param minOutputTokens  输出配额下限
 * @param tunableSampling  是否接受 temperature / top_p
 */
public record ModelTraits(boolean reasoning, boolean jsonObject,
                          int minOutputTokens, boolean tunableSampling) {

    private static final ModelTraits PLAIN = new ModelTraits(false, true, 0, true);

    /**
     * 推理模型的思维链与正文共享输出配额，配额给小了会返回 200 但正文为空；
     * 且思考模式下 JSON 常落进思维链字段，response_format 不可靠。
     */
    private static final ModelTraits REASONING = new ModelTraits(true, false, 16384, false);

    /** r1 / o1 / o3 要按独立片段匹配，避免 model-o1x 这类误判。 */
    private static final Pattern REASONING_RE = Pattern.compile(
            "reasoner|reasoning|thinking|qwq"
                    + "|(?:^|[^a-z0-9])r1(?:$|[^a-z0-9])"
                    + "|(?:^|[^a-z0-9])o[13](?:$|[^a-z0-9])");

    public static ModelTraits of(String model) {
        if (model == null) {
            return PLAIN;
        }
        String key = model.strip().toLowerCase(Locale.ROOT);
        return REASONING_RE.matcher(key).find() ? REASONING : PLAIN;
    }
}
