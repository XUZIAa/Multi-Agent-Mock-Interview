package com.interviewer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.Text;
import com.interviewer.core.error.ConfigException;
import com.interviewer.core.error.InterviewerException;
import com.interviewer.core.error.ProviderException;
import com.interviewer.core.error.ProviderResponseException;
import com.interviewer.core.provider.ModelTraits;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

/**
 * 一个 (供应商, 模型) 的调用入口。
 *
 * <p>九个供应商全部遵循 OpenAI 兼容协议，所以只有这一条路径——换模型只换 baseUrl 与
 * model。差异都在 {@link ModelTraits} 里，由它决定要不要下发 temperature、能不能用
 * response_format、输出配额的下限是多少。
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** 结构化输出的配额下限。给太小会出现 HTTP 200 但正文为空，或 JSON 被截断在半路。 */
    private static final int MIN_STRUCTURED_TOKENS = 1024;

    private static final String JSON_ONLY_HINT =
            "只输出一个合法 JSON 对象，不要任何解释文字，不要代码围栏。";

    private final String role;
    private final String providerKey;
    private final String model;
    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final LooseJson loose;
    private final ModelTraits traits;

    /** limiter 为 null 表示不限流，只有连通性探测走这条路。 */
    private final LlmRateLimiter limiter;

    LlmClient(String role, String providerKey, String model, ChatModel chatModel,
              ObjectMapper mapper, LooseJson loose, LlmRateLimiter limiter) {
        this.role = role;
        this.providerKey = providerKey;
        this.model = model;
        this.chatModel = chatModel;
        this.mapper = mapper;
        this.loose = loose;
        this.limiter = limiter;
        this.traits = ModelTraits.of(model);
    }

    public String providerKey() {
        return providerKey;
    }

    public String model() {
        return model;
    }

    public String complete(List<Message> messages) {
        return complete(messages, 0.6, 2048, false);
    }

    public String complete(List<Message> messages, double temperature, int maxTokens,
                           boolean jsonMode) {
        Prompt prompt = new Prompt(messages, options(temperature, maxTokens, jsonMode));
        ChatResponse response = limiter == null
                ? call(prompt)
                : limiter.guard(role, () -> call(prompt));
        Generation result = response == null ? null : response.getResult();
        if (result == null || result.getOutput() == null) {
            throw new ProviderResponseException("响应里没有生成结果", providerKey);
        }
        String content = Text.safe(result.getOutput().getText());
        if (content.isBlank()) {
            throw emptyBody(result);
        }
        return content;
    }

    private ChatResponse call(Prompt prompt) {
        try {
            return chatModel.call(prompt);
        } catch (InterviewerException e) {
            // ProviderErrorHandler 抛出来的，已经带了精确状态码，原样上抛
            throw e;
        } catch (Exception e) {
            throw new ProviderException(rootMessage(e), null, providerKey, e);
        }
    }

    /**
     * 要求模型按 schema 输出。解析失败会带着错误信息重问一次。
     *
     * <p>先经 {@link LooseJson} 归一形态再绑定：模型对同一字段给字符串还是对象是随机的，
     * 严格绑定会让整份复盘缺一块。
     */
    public <T> T structured(List<Message> messages, Class<T> schema) {
        return structured(messages, schema, 0.3, 3072, 1);
    }

    public <T> T structured(List<Message> messages, Class<T> schema, double temperature,
                            int maxTokens, int retries) {
        if (traits.reasoning()) {
            // 抬配额治不好：思维链和正文共享额度，题库这种长输出怎么给都不够，
            // 且思考模式下 JSON 常落进思维链字段。本项目全是结构化输出，直接挡住并指路。
            throw new ConfigException("推理模型不支持结构化输出: " + model,
                    "模型「" + model + "」是推理模型，无法稳定输出结构化 JSON。"
                            + "请到「设置 → 角色模型绑定」换成非推理模型，例如 deepseek-chat。");
        }
        List<Message> convo = new ArrayList<>(messages);
        if (!traits.jsonObject()) {
            // 没有 response_format 强约束时，用提示词把格式要求兜住
            convo.add(new UserMessage(JSON_ONLY_HINT));
        }

        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            String text = complete(convo, temperature, maxTokens, true);
            try {
                JsonNode json = JsonExtract.parse(mapper, text);
                return loose.parse(json, schema);
            } catch (ProviderResponseException e) {
                lastError = e;
                log.warn("结构化输出解析失败(第 {} 次): {}", attempt + 1, Text.cut(e.getMessage(), 300));
                if (attempt >= retries) {
                    break;
                }
                convo = new ArrayList<>(convo);
                convo.add(new AssistantMessage(Text.cut(text, 2000)));
                convo.add(new UserMessage("上面的输出不符合要求，解析报错：\n"
                        + Text.cut(Text.safe(e.getMessage()), 600) + "\n"
                        + "请只输出一个合法 JSON 对象，不要任何解释文字和代码围栏。"));
            }
        }
        throw new ProviderResponseException(
                lastError == null ? "结构化输出解析失败" : Text.safe(lastError.getMessage()),
                providerKey);
    }

    private OpenAiChatOptions options(double temperature, int maxTokens, boolean jsonMode) {
        int floor = traits.minOutputTokens();
        if (jsonMode) {
            floor = Math.max(floor, MIN_STRUCTURED_TOKENS);
        }
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model)
                .maxTokens(Math.max(maxTokens, floor));
        if (traits.tunableSampling()) {
            builder.temperature(temperature);
        }
        if (jsonMode && traits.jsonObject()) {
            builder.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, (String) null));
        }
        return builder.build();
    }

    /** 空正文要说清到底为什么空，否则用户只看到「无法解析」无从下手。 */
    private ProviderResponseException emptyBody(Generation result) {
        String finish = result.getMetadata() == null
                ? "" : Text.safe(result.getMetadata().getFinishReason());
        if (traits.reasoning() || "length".equalsIgnoreCase(finish)) {
            return new ProviderResponseException(
                    "模型「" + model + "」只输出了思维链就用尽了配额，正文为空。"
                            + "推理模型的思维链与正文共享 max_tokens，"
                            + "请改用非推理模型（如 deepseek-chat）", providerKey);
        }
        return new ProviderResponseException(
                "模型「" + model + "」返回了空内容（finish_reason="
                        + (finish.isEmpty() ? "未知" : finish) + "）", providerKey);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return Text.notBlank(msg) ? msg : cur.getClass().getSimpleName();
    }
}
