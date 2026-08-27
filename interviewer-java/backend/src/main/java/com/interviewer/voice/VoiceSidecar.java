package com.interviewer.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.AppPaths;
import com.interviewer.core.error.RealtimeException;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Python 语音 sidecar 的进程管理与行协议。
 *
 * <p>为什么语音留在 Python：音频采集与播放靠 sounddevice（PortAudio），回声抑制靠 numpy
 * 的 FFT 互相关，播放缓冲的预蓄水与抖动处理是逐毫秒调过的。这一层在 JVM 上重写要用
 * JavaSound 加 JTransforms，而 JavaSound 在 Windows 上的设备枚举与延迟都不如 PortAudio，
 * 重调一遍的成本远大于维护一个 sidecar。
 *
 * <p>协议是逐行 JSON：stdin 收命令，stdout 回事件。二进制音频完全不过这条线——PCM 每 40ms
 * 一块，跨进程传纯属浪费，录音也因此留在 Python 侧落盘。
 */
@Component
public class VoiceSidecar {

    private static final Logger log = LoggerFactory.getLogger(VoiceSidecar.class);

    private static final String READY_PREFIX = "VOICE_READY ";

    @Value("${interviewer.voice.handshake-timeout:30s}")
    private Duration handshakeTimeout = Duration.ofSeconds(30);

    /**
     * 打包后的可执行文件路径。
     *
     * <p>用属性而不是直接读环境变量：外壳照旧设 {@code INTERVIEWER_VOICE_EXE}，Spring 的
     * 松散绑定会把它映射到这里，同时测试与本机调试还能用 {@code -D} 覆盖。
     */
    @Value("${interviewer.voice.exe:}")
    private String voiceExe = "";

    /** 开发期的 voice-sidecar 项目目录，用 uv 直接跑源码。 */
    @Value("${interviewer.voice.dir:}")
    private String voiceDir = "";

    private final ObjectMapper mapper;
    private final AtomicReference<Process> process = new AtomicReference<>();
    private BufferedWriter stdin;
    private Consumer<JsonNode> listener;

    public VoiceSidecar(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 启动并等到 sidecar 报出就绪。已在运行时直接复用。 */
    public synchronized void ensureStarted(Consumer<JsonNode> eventListener) {
        this.listener = eventListener;
        Process current = process.get();
        if (current != null && current.isAlive()) {
            return;
        }
        Launch launch = resolveLaunch();
        log.info("启动语音 sidecar: {} (cwd={})", String.join(" ", launch.command()),
                launch.workingDir());

        ProcessBuilder builder = new ProcessBuilder(launch.command());
        builder.directory(launch.workingDir().toFile());
        builder.redirectErrorStream(false);
        // 中文日志与提示词都要 UTF-8，Windows 默认 GBK 会乱码
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put("INTERVIEWER_DATA_ROOT", AppPaths.dataRoot().toString());

        Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            throw new RealtimeException("语音 sidecar 启动失败: " + e.getMessage(), e);
        }
        process.set(started);
        stdin = new BufferedWriter(new OutputStreamWriter(
                started.getOutputStream(), StandardCharsets.UTF_8));

        CountDownLatch ready = new CountDownLatch(1);
        drainStdout(started, ready);
        drainStderr(started);

        try {
            if (!ready.await(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stop();
                throw new RealtimeException("语音 sidecar 未在 "
                        + handshakeTimeout.toSeconds() + " 秒内就绪");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new RealtimeException("等待语音 sidecar 就绪被中断", e);
        }
    }

    private void drainStdout(Process started, CountDownLatch ready) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith(READY_PREFIX)) {
                        log.info("语音 sidecar 就绪: {}", line.substring(READY_PREFIX.length()));
                        ready.countDown();
                        continue;
                    }
                    dispatch(line);
                }
            } catch (IOException e) {
                log.info("语音 sidecar 输出流结束: {}", e.getMessage());
            }
            ready.countDown();
            log.info("语音 sidecar 已退出");
        }, "voice-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    /** sidecar 的日志走 stderr，转发到本进程日志，出问题时能在同一份日志里对齐时间。 */
    private void drainStderr(Process started) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    started.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    log.info("[voice] {}", line);
                }
            } catch (IOException ignored) {
                // 进程退出时正常发生
            }
        }, "voice-stderr");
        reader.setDaemon(true);
        reader.start();
    }

    private void dispatch(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            JsonNode event = mapper.readTree(line);
            Consumer<JsonNode> target = listener;
            if (target != null) {
                target.accept(event);
            }
        } catch (Exception e) {
            log.warn("语音事件解析失败: {}", line.length() > 200 ? line.substring(0, 200) : line);
        }
    }

    /** 发一条命令。sidecar 已死时抛错，由编排层转成用户可见的失败。 */
    public synchronized void send(Object command) {
        Process current = process.get();
        if (current == null || !current.isAlive() || stdin == null) {
            throw new RealtimeException("语音 sidecar 未在运行");
        }
        try {
            stdin.write(mapper.writeValueAsString(command));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new RealtimeException("向语音 sidecar 发送命令失败: " + e.getMessage(), e);
        }
    }

    /**
     * 枚举音频设备。
     *
     * <p>另起一个短命进程而不是复用长驻那个：设置页要在没有面试时也能列设备，而长驻进程
     * 只在面试期间存在。设备枚举是纯查询，问完就退。
     *
     * @return {@code {"inputs":[...],"outputs":[...]}}，失败返回 null 让界面回落到系统默认
     */
    public JsonNode listDevices() {
        try {
            Launch launch = resolveLaunch();
            List<String> command = new ArrayList<>(launch.command());
            command.add("--list-devices");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(launch.workingDir().toFile());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            builder.redirectErrorStream(false);
            Process probe = builder.start();
            String line;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    probe.getInputStream(), StandardCharsets.UTF_8))) {
                line = in.readLine();
            }
            if (!probe.waitFor(20, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
            }
            return line == null || line.isBlank() ? null : mapper.readTree(line);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("枚举音频设备失败: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public synchronized void stop() {
        Process current = process.getAndSet(null);
        if (current == null) {
            return;
        }
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // 关闭 stdin 就是让 sidecar 自己收摊的信号
        }
        stdin = null;
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    /** 启动方式。工作目录必须一起带上：uv 靠它找到项目，打包版靠它找到同目录的动态库。 */
    private record Launch(List<String> command, Path workingDir) {
    }

    /**
     * 解析启动方式。
     *
     * <p>由外壳（Tauri）显式告知，不做路径猜测：打包版和开发版的目录结构完全不同，猜错了
     * 只会得到「进程起不来」这种没有指向性的错误。
     *
     * <p>{@code INTERVIEWER_VOICE_EXE} 是打包后的可执行文件；{@code INTERVIEWER_VOICE_DIR}
     * 是开发期的 voice-sidecar 项目目录，用 uv 直接跑源码。
     */
    private Launch resolveLaunch() {
        if (!voiceExe.isBlank()) {
            Path path = Paths.get(voiceExe.strip());
            if (!Files.isExecutable(path)) {
                throw new RealtimeException("interviewer.voice.exe 指向的文件不可执行: " + path);
            }
            return new Launch(List.of(path.toString()), path.toAbsolutePath().getParent());
        }
        if (!voiceDir.isBlank()) {
            Path path = Paths.get(voiceDir.strip());
            if (!Files.isDirectory(path)) {
                throw new RealtimeException("interviewer.voice.dir 指向的目录不存在: " + path);
            }
            return new Launch(List.of("uv", "run", "python", "-m", "voice.main"), path);
        }
        throw new RealtimeException("没有配置语音 sidecar 的位置，请设置 "
                + "INTERVIEWER_VOICE_EXE（打包版可执行文件）或 INTERVIEWER_VOICE_DIR（源码目录）");
    }
}
