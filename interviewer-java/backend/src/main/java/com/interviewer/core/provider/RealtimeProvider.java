package com.interviewer.core.provider;

import java.util.List;

/**
 * 端到端实时语音接入点，协议为 OpenAI Realtime 事件风格。
 *
 * <p>{@code credentialKey} 指向共用同一份 API Key 的文本供应商——百炼的实时语音
 * 与文本模型是同一个 Key。
 *
 * <p>这份目录留在 Java 侧是因为「设置」页要渲染它，实际连接由 Python 语音
 * sidecar 建立，握手时把选中的供应商参数下发过去。
 */
public record RealtimeProvider(String key, String label, String credentialKey, String wsUrl,
                               String defaultModel, List<String> models, List<String> voices,
                               String defaultVoice, String consoleUrl,
                               int inputSampleRate, int outputSampleRate,
                               String audioFormat, boolean supportsSemanticVad) {
}
