package com.interviewer.core.provider;

import com.interviewer.core.error.ConfigException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商、模型、音色与角色的静态目录。
 *
 * <p>前端的下拉全部从这里生成（/catalog 接口），所以常量只在这一处维护，
 * 前端不再抄一份。
 */
public final class Providers {

    // ---------- 文本模型角色 ----------

    public static final String ROLE_DIRECTOR = "director";
    public static final String ROLE_ANALYST = "analyst";
    public static final String ROLE_GUARD = "guard";
    public static final String ROLE_ASSIST = "assist";

    public static final List<String> ALL_ROLES =
            List.of(ROLE_DIRECTOR, ROLE_ANALYST, ROLE_GUARD, ROLE_ASSIST);

    public static final Map<String, String> ROLE_LABELS = roleLabels();

    // ---------- 文本供应商 ----------

    public static final String KEY_DEEPSEEK = "deepseek";
    public static final String KEY_DASHSCOPE = "dashscope";
    public static final String KEY_CUSTOM = "custom";
    public static final String KEY_OPENAI_COMPAT = "openai_compat";

    public static final Map<String, ChatProvider> CHAT = index(List.of(
            new ChatProvider(KEY_DEEPSEEK, "DeepSeek",
                    "https://api.deepseek.com/v1", "deepseek-chat",
                    List.of("deepseek-chat", "deepseek-reasoner"),
                    "https://platform.deepseek.com/api_keys"),
            new ChatProvider(KEY_DASHSCOPE, "阿里云百炼 / 通义千问",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
                    List.of("qwen-plus", "qwen-flash", "qwen-max", "qwen3-max"),
                    "https://bailian.console.aliyun.com/?apiKey=1"),
            new ChatProvider("moonshot", "月之暗面 Kimi",
                    "https://api.moonshot.cn/v1", "kimi-k2-turbo-preview",
                    List.of("kimi-k2-turbo-preview", "kimi-k2-0905-preview", "moonshot-v1-128k"),
                    "https://platform.moonshot.cn/console/api-keys"),
            new ChatProvider("zhipu", "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4.6",
                    List.of("glm-4.6", "glm-4.5-air", "glm-4-flash"),
                    "https://bigmodel.cn/usercenter/apikeys"),
            new ChatProvider("volcengine", "火山引擎 豆包",
                    "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-250615",
                    List.of("doubao-seed-1-6-250615", "doubao-1-5-pro-32k-250115"),
                    "https://console.volcengine.com/ark"),
            new ChatProvider("minimax", "MiniMax",
                    "https://api.minimax.chat/v1", "MiniMax-M2",
                    List.of("MiniMax-M2", "abab6.5s-chat"),
                    "https://platform.minimaxi.com/user-center/basic-information"),
            new ChatProvider("stepfun", "阶跃星辰 Step",
                    "https://api.stepfun.com/v1", "step-2-mini",
                    List.of("step-2-mini", "step-2-16k", "step-1-8k"),
                    "https://platform.stepfun.com/interface-key"),
            new ChatProvider(KEY_CUSTOM, "自定义 OpenAI 兼容端点",
                    "http://127.0.0.1:11434/v1", "qwen3:14b",
                    List.of(), ""),
            new ChatProvider(KEY_OPENAI_COMPAT, "自定义 OpenAI 兼容中转",
                    "", "", List.of(), "")));

    public static final Map<String, RoleBinding> DEFAULT_ROLE_BINDINGS = defaultBindings();

    // ---------- 实时语音供应商 ----------

    private static final List<String> QWEN_VOICES = List.of(
            "Cherry", "Serena", "Ethan", "Chelsie", "Nofish",
            "Jennifer", "Ryan", "Katerina", "Elias", "Tina");

    public static final Map<String, RealtimeProvider> REALTIME = indexRealtime(List.of(
            new RealtimeProvider("qwen_omni", "通义千问 Omni Realtime（百炼）",
                    KEY_DASHSCOPE, "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
                    "qwen3-omni-flash-realtime",
                    List.of("qwen3-omni-flash-realtime", "qwen3-omni-plus-realtime",
                            "qwen-omni-turbo-realtime"),
                    QWEN_VOICES, "Ethan",
                    "https://bailian.console.aliyun.com/?apiKey=1",
                    16000, 24000, "pcm", true),
            new RealtimeProvider("stepaudio", "阶跃星辰 StepAudio Realtime",
                    "stepfun", "wss://api.stepfun.com/v1/realtime",
                    "stepaudio-2.5-realtime",
                    List.of("stepaudio-2.5-realtime"),
                    List.of("linjiajiejie", "jilingshaonv", "wenrounanyou", "zhengjingnansheng"),
                    "zhengjingnansheng",
                    "https://platform.stepfun.com/interface-key",
                    16000, 24000, "pcm16", true)));

    public static final Map<String, String> VOICE_LABELS = voiceLabels();

    private Providers() {
    }

    public static ChatProvider chat(String key) {
        ChatProvider provider = CHAT.get(key);
        if (provider == null) {
            throw new ConfigException("未知的模型供应商: " + key);
        }
        return provider;
    }

    public static RealtimeProvider realtime(String key) {
        RealtimeProvider provider = REALTIME.get(key);
        if (provider == null) {
            throw new ConfigException("未知的实时语音供应商: " + key);
        }
        return provider;
    }

    /** 供应商展示名。给「尚未配置 API Key」这类话术用。 */
    public static String displayName(String providerKey) {
        ChatProvider c = CHAT.get(providerKey);
        if (c != null) {
            return c.label();
        }
        RealtimeProvider r = REALTIME.get(providerKey);
        return r != null ? r.label() : providerKey;
    }

    /** 所有可能持有 API Key 的供应商键。 */
    public static List<String> credentialKeys() {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        CHAT.keySet().forEach(k -> keys.put(k, true));
        REALTIME.values().forEach(r -> keys.put(r.credentialKey(), true));
        return List.copyOf(keys.keySet());
    }

    /**
     * 目录一律用 LinkedHashMap 包一层，不用 Map.copyOf。
     *
     * <p>Map.copyOf 的迭代顺序是哈希顺序，且每次启动都可能不同。设置页的下拉框直接按这里的
     * 顺序渲染，那样用户每次打开看到的排列都不一样。
     */
    private static Map<String, ChatProvider> index(List<ChatProvider> list) {
        LinkedHashMap<String, ChatProvider> map = new LinkedHashMap<>();
        list.forEach(p -> map.put(p.key(), p));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, RealtimeProvider> indexRealtime(List<RealtimeProvider> list) {
        LinkedHashMap<String, RealtimeProvider> map = new LinkedHashMap<>();
        list.forEach(p -> map.put(p.key(), p));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, RoleBinding> defaultBindings() {
        LinkedHashMap<String, RoleBinding> map = new LinkedHashMap<>();
        for (String role : ALL_ROLES) {
            map.put(role, new RoleBinding(KEY_DEEPSEEK, "deepseek-chat"));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> roleLabels() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put(ROLE_DIRECTOR, "导演（面试节奏决策 / 代码追问）");
        map.put(ROLE_ANALYST, "分析师（简历诊断 / 复盘评分）");
        map.put(ROLE_GUARD, "守卫（人格漂移检测）");
        map.put(ROLE_ASSIST, "助手（STAR 判定 / 实时提词）");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> voiceLabels() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("Cherry", "Cherry · 女声 明亮");
        map.put("Serena", "Serena · 女声 沉稳");
        map.put("Ethan", "Ethan · 男声 干练");
        map.put("Chelsie", "Chelsie · 女声 温和");
        map.put("Nofish", "Nofish · 男声 低沉");
        map.put("Jennifer", "Jennifer · 女声 外企腔");
        map.put("Ryan", "Ryan · 男声 强势");
        map.put("Katerina", "Katerina · 女声 冷峻");
        map.put("Elias", "Elias · 男声 儒雅");
        map.put("Tina", "Tina · 女声 轻快");
        map.put("linjiajiejie", "邻家姐姐 · 女声 亲和");
        map.put("jilingshaonv", "机灵少女 · 女声 活泼");
        map.put("wenrounanyou", "温柔男友 · 男声 温和");
        map.put("zhengjingnansheng", "正经男声 · 男声 严肃");
        return Collections.unmodifiableMap(map);
    }
}
