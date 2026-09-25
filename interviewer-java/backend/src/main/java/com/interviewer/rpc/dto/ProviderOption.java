package com.interviewer.rpc.dto;

import java.util.List;

public record ProviderOption(String key, String label, String credentialKey, String consoleUrl,
                             String defaultModel, List<String> models, List<ModelOption> voices) {
}
