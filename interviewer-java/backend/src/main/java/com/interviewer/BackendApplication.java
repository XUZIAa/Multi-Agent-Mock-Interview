package com.interviewer;

import com.interviewer.core.AppPaths;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

/**
 * 后端独立进程入口。
 *
 * <p>Tauri 以 sidecar 方式拉起它：连接信息通过 stdout 握手行回报，父进程据此连上来。
 * 端口写死会撞车，所以默认交给系统分配，实际值只能从这一行得知。
 *
 * <p>stdout 是握手行的专用通道，日志全部走 stderr（见 logback-spring.xml）。
 */
@SpringBootApplication
public class BackendApplication {

    private static final Logger log = LoggerFactory.getLogger(BackendApplication.class);

    public static final String HANDSHAKE_PREFIX = "INTERVIEWER_RPC ";
    private static final String LOOPBACK = "127.0.0.1";

    public static void main(String[] args) {
        // logback 要在 Spring 之前拿到日志目录
        System.setProperty(AppPaths.LOG_DIR_PROPERTY, AppPaths.logDir().toString());

        Args parsed = Args.parse(args);

        log.info("后端进程启动 pid={} javaHome={}", ProcessHandle.current().pid(),
                System.getProperty("java.home"));

        // 必须作为命令行参数传进去，不能用 SpringApplicationBuilder.properties()：
        // 那落的是 defaultProperties，优先级低于 application.yml，而 yml 里写着
        // server.port: 0——结果 --port 永远不生效，想固定端口调试都做不到
        String[] merged = new String[args.length + 2];
        System.arraycopy(args, 0, merged, 0, args.length);
        merged[args.length] = "--server.port=" + parsed.port;
        merged[args.length + 1] = "--interviewer.token=" + parsed.token;

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(merged);

        watchStdin(ctx);
    }

    /**
     * 父进程退出会关掉 stdin，据此感知并跟着收摊，避免留下孤儿进程。
     *
     * <p>非守护线程：它得撑住 JVM，否则 main 返回后进程就没了。
     */
    private static void watchStdin(ConfigurableApplicationContext ctx) {
        Thread watcher = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("shutdown".equals(line.trim())) {
                        log.info("收到 shutdown 指令，开始收尾");
                        break;
                    }
                }
                if (line == null) {
                    log.info("父进程已关闭 stdin，开始收尾");
                }
            } catch (Exception e) {
                log.warn("stdin 监视中断: {}", e.toString());
            }
            ctx.close();
            log.info("后端进程退出");
        }, "stdin-watch");
        watcher.setDaemon(false);
        watcher.start();
    }

    /**
     * 报出真实端口。
     *
     * <p>挂在事件上而不是 run() 之后取：端口是 0 时只有容器起来才知道分到了谁。
     */
    @Component
    static class Handshake {

        private final AtomicBoolean announced = new AtomicBoolean(false);
        private final String token;

        Handshake(org.springframework.core.env.Environment env) {
            this.token = env.getProperty("interviewer.token", "");
        }

        @EventListener
        void onReady(WebServerInitializedEvent event) {
            if (!announced.compareAndSet(false, true)) {
                return;
            }
            int port = event.getWebServer().getPort();
            announce(port, token);
            log.info("后端进程就绪 port={}", port);
        }
    }

    /**
     * 把连接信息写到 stdout 供父进程读取。
     *
     * <p>刻意绕开 System.out：它的编码取决于启动参数，而这一行必须逐字节可预期。
     * 载荷全是 ASCII，用固定字符集包一层 FileDescriptor.out 最稳。
     */
    static void announce(int port, String token) {
        String payload = "{\"port\":" + port
                + ",\"token\":\"" + token + "\""
                + ",\"host\":\"" + LOOPBACK + "\"}";
        PrintStream out = new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.US_ASCII);
        out.print(HANDSHAKE_PREFIX + payload + "\n");
        out.flush();
    }

    /** 命令行参数。与 Python 版的 argparse 保持同名同义。 */
    record Args(int port, String token) {

        static Args parse(String[] argv) {
            int port = 0;
            String token = "";
            for (int i = 0; i < argv.length - 1; i++) {
                switch (argv[i]) {
                    case "--port" -> port = parsePort(argv[i + 1]);
                    case "--token" -> token = argv[i + 1];
                    default -> {
                    }
                }
            }
            return new Args(port, token.isBlank() ? randomToken() : token);
        }

        private static int parsePort(String raw) {
            try {
                int value = Integer.parseInt(raw.trim());
                return (value >= 0 && value <= 65535) ? value : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String randomToken() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
