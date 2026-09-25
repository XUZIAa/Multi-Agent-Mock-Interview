package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.TurnIntent;
import com.interviewer.llm.PromptResource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锚点文案的逐字比对。
 *
 * <p>这些是面试官的行为约束本体：环节指引限定提问范围，动作要求对应一个个真实出现过的坏
 * 行为（比如「不要夸、不要说谢谢分享」）。少一个字都可能让模型换一种表现，所以按原版逐字校。
 */
class AnchorTextParityTest {

    @Test
    void everyPhaseAndActionTextMatchesTheOriginal() throws Exception {
        JsonNode want = Parity.load("anchor-text");
        assertNotNull(want, "缺少 parity/anchor-text.json 基准");

        List<String> mismatched = new ArrayList<>();
        check(want, "anchor_workflow", PromptResource.load("anchor_workflow"), mismatched);
        for (InterviewPhase phase : InterviewPhase.values()) {
            String key = "anchor_phase_" + phase.value();
            check(want, key, PromptResource.load(key), mismatched);
        }
        for (TurnIntent intent : TurnIntent.values()) {
            String key = "anchor_action_" + intent.value();
            check(want, key, PromptResource.load(key), mismatched);
        }
        assertTrue(mismatched.isEmpty(), "锚点文案与原版不一致: " + mismatched);

        // 基准里的每一段都必须被上面这三类覆盖，否则是漏搬了资源
        assertEquals(want.size(), 1 + InterviewPhase.values().length + TurnIntent.values().length,
                "锚点文案的段数与原版不符");
    }

    /** 基准存的是字数与摘要而不是原文：原文已经在 resources/prompts 下，存两份迟早改歪一份。 */
    private static void check(JsonNode want, String key, String actual, List<String> out) {
        JsonNode expected = want.get(key);
        if (expected == null) {
            out.add(key + "（基准里没有这一段）");
            return;
        }
        int chars = expected.path("chars").asInt();
        String digest = expected.path("sha256").asText();
        if (actual.length() != chars) {
            out.add(key + "（字数 " + actual.length() + "，应为 " + chars + "）");
            return;
        }
        if (!sha256(actual).equals(digest)) {
            out.add(key + "（内容摘要不符）");
        }
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
