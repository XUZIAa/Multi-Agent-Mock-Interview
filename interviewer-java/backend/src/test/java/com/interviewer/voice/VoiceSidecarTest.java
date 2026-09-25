package com.interviewer.voice;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 语音 sidecar 的跨进程握手。
 *
 * <p>这是整套重构里唯一的进程边界，也是最容易出错的地方：Windows 上非终端的 stdout 是块
 * 缓冲的，握手行不显式 flush 就永远等不到；中文日志不按 UTF-8 走就是一片乱码。这些都编译
 * 不出来，只能真起一次进程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VoiceSidecarTest {

    private static Path sidecarDir;

    @Autowired
    private VoiceSidecar sidecar;

    @BeforeAll
    static void locate() {
        // 测试从 backend/ 下跑，voice-sidecar 与它并列
        sidecarDir = Path.of("").toAbsolutePath().getParent().resolve("voice-sidecar");
    }

    @DynamicPropertySource
    static void voiceLocation(DynamicPropertyRegistry registry) {
        registry.add("interviewer.voice.dir",
                () -> Path.of("").toAbsolutePath().getParent().resolve("voice-sidecar").toString());
    }

    @Test
    void handshakeCompletesAndCommandsAreAccepted() {
        assumeTrue(Files.isRegularFile(sidecarDir.resolve("pyproject.toml")),
                "找不到 voice-sidecar 项目，跳过跨进程用例");

        List<JsonNode> events = new CopyOnWriteArrayList<>();
        sidecar.ensureStarted(events::add);

        // 还没建会话就发命令：应当收到一条明确的错误事件，而不是静默或崩掉
        sidecar.send(Map.of("cmd", "mute", "muted", true));
        JsonNode error = await(events, "error");
        assertNotNull(error, "没有收到语音侧的错误事件");
        // 这句话本身就是 UTF-8 的验收：编码错了它会变成一串问号或乱码
        String message = error.path("message").asText();
        assertTrue(message.contains("语音会话"), "错误话术应说明是会话没建立：" + message);

        // close 必须回一条 audio_saved，否则 Java 侧的收尾会一直等到超时
        sidecar.send(Map.of("cmd", "close", "reason", "测试收尾"));
        assertNotNull(await(events, "audio_saved"), "close 之后没有回报录音路径");

        sidecar.stop();
    }

    @Test
    void devicesAreEnumerable() {
        assumeTrue(Files.isRegularFile(sidecarDir.resolve("pyproject.toml")),
                "找不到 voice-sidecar 项目，跳过跨进程用例");
        JsonNode devices = sidecar.listDevices();
        assumeTrue(devices != null, "这台机器枚举不到音频设备，跳过");
        assertTrue(devices.path("inputs").isArray(), "输入设备应当是数组");
        assertTrue(devices.path("outputs").isArray(), "输出设备应当是数组");
    }

    /** 事件从 sidecar 的读线程异步进来，只能等。 */
    private static JsonNode await(List<JsonNode> events, String name) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (JsonNode event : events) {
                if (name.equals(event.path("event").asText())) {
                    return event;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
