package com.interviewer.analysis;

import com.interviewer.core.Text;
import com.interviewer.core.error.ConfigException;
import com.interviewer.domain.coding.CaseOutcome;
import com.interviewer.domain.coding.Coding;
import com.interviewer.domain.coding.CodingCase;
import com.interviewer.domain.coding.JudgeOutcome;
import com.interviewer.domain.coding.RunOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 在子进程里跑候选人的代码。超时强杀，输出截断。
 *
 * <p>与 Python 版的一处实质差异：那边打包后自带 Python 运行时，靠入口壳的
 * {@code --exec-python} 开关转去执行用户代码。Java 后端没有自带 Python，
 * 所以两种语言都依赖用户机器上的运行时，取不到就明确报错并指路——
 * 不假装能跑，也不悄悄换语言。
 */
@Component
public class CodeRunner {

    private static final Logger log = LoggerFactory.getLogger(CodeRunner.class);

    private static final Map<String, String> SUFFIX = Map.of(
            "python", ".py", "javascript", ".js");

    /** 候选可执行名，按命中概率排序。Windows 上 python 也常见叫 py。 */
    private static final Map<String, List<String>> CANDIDATES = Map.of(
            "python", List.of("python", "python3", "py"),
            "javascript", List.of("node"));

    /** 探测用的一行代码。必须真跑一次，见 {@link #usable}。 */
    private static final Map<String, List<String>> PROBE = Map.of(
            "python", List.of("-c", "print(1)"),
            "javascript", List.of("-e", "console.log(1)"));

    /**
     * 已探测确认可用的解释器。
     *
     * <p>不能只看「PATH 里有这个文件且可执行」：Windows 10/11 默认就在
     * {@code WindowsApps} 下放着 python3.exe 这类应用商店存根，它存在、可执行，
     * 但一跑就立刻退出且没有任何输出。撞上它，编码环节会整个哑掉且看不出原因。
     *
     * <p>只缓存成功结果：用户装完运行时不该还得重启应用。
     */
    private final Map<String, String> usable = new ConcurrentHashMap<>();

    public RunOutcome run(String language, String source, String stdinText) {
        return run(language, source, stdinText, Coding.RUN_TIMEOUT_MS);
    }

    public RunOutcome run(String language, String source, String stdinText, int timeoutMs) {
        String suffix = SUFFIX.get(language);
        if (suffix == null) {
            throw new ConfigException("不支持运行 " + language);
        }
        List<String> base = interpreter(language);

        Path dir = null;
        try {
            dir = Files.createTempDirectory("interviewer-run-");
            Path file = dir.resolve("main" + suffix);
            Files.writeString(file, Text.safe(source), StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(base);
            command.add(file.toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            // 用户代码的中文输出要能读出来
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");

            long started = System.nanoTime();
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new ConfigException("启动运行进程失败：" + e.getMessage());
            }

            try (var stdin = process.getOutputStream()) {
                stdin.write(Text.safe(stdinText).getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException ignored) {
                // 程序不读输入就直接关掉管道了，正常情况
            }

            // 必须并发读两个流：任一管道填满都会让子进程阻塞在写上，然后一起等到超时
            StreamPump out = StreamPump.start(process.getInputStream(), "run-stdout");
            StreamPump err = StreamPump.start(process.getErrorStream(), "run-stderr");

            boolean exited;
            try {
                exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return RunOutcome.timeout("运行被中断", elapsedMs(started));
            }

            if (!exited) {
                process.destroyForcibly();
                // 回收管道，否则读线程会挂着
                out.join();
                err.join();
                return RunOutcome.timeout(
                        "运行超过 " + (timeoutMs / 1000) + " 秒被终止，检查是否死循环",
                        elapsedMs(started));
            }

            int code = process.exitValue();
            return new RunOutcome(code == 0, clip(out.join()), clip(err.join()),
                    code, elapsedMs(started), false);
        } catch (IOException e) {
            throw new ConfigException("准备运行环境失败：" + e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 逐条跑用例。串行执行：并行会让超时判断互相干扰。 */
    public JudgeOutcome judge(String language, String source, List<CodingCase> cases) {
        List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            CodingCase item = cases.get(i);
            RunOutcome result = run(language, source, item.input());
            boolean passed = result.ok() && sameOutput(result.stdout(), item.expected());
            outcomes.add(new CaseOutcome(i, passed, item.input(), item.expected(),
                    result.stdout(), result.stderr(), result.durationMs(), result.timedOut()));
        }
        return JudgeOutcome.of(outcomes);
    }

    /** 按行比较并忽略行尾空白，避免因为一个换行判错。 */
    static boolean sameOutput(String actual, String expected) {
        return splitLines(actual).equals(splitLines(expected));
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : Text.safe(text).strip().split("\\R")) {
            int end = line.length();
            while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
                end--;
            }
            out.add(line.substring(0, end));
        }
        // strip 之后的空串会 split 出单个空行，去掉它让空输出等于空输出
        if (out.size() == 1 && out.get(0).isEmpty()) {
            return List.of();
        }
        return out;
    }

    private List<String> interpreter(String language) {
        String cached = usable.get(language);
        if (cached != null) {
            return List.of(cached);
        }
        List<String> stubs = new ArrayList<>();
        for (String name : CANDIDATES.get(language)) {
            Path found = which(name);
            if (found == null) {
                continue;
            }
            if (!works(language, name)) {
                stubs.add(name + " → " + found);
                continue;
            }
            log.info("代码沙盒使用 {}（{}）", name, found);
            usable.put(language, name);
            return List.of(name);
        }
        throw missing(language, stubs);
    }

    private static ConfigException missing(String language, List<String> stubs) {
        // 有存根却不可用是最容易卡住人的情况，话术里必须点明，否则用户会以为自己装过了
        String hint = stubs.isEmpty() ? ""
                : "（PATH 里的 " + String.join("、", stubs) + " 只是占位存根，跑不出东西）";
        if ("python".equals(language)) {
            return new ConfigException("PATH 中没有可用的 python: " + stubs,
                    "没找到可用的 Python 运行时" + hint
                            + "。到 python.org 装一个并勾选加入 PATH，或改用 JavaScript");
        }
        return new ConfigException("PATH 中没有可用的 node: " + stubs,
                "没找到可用的 Node.js" + hint + "。装好 Node 后再试，或改用 Python");
    }

    /** 真跑一行代码确认它能用。存根会立刻退出且没有输出，据此区分。 */
    private static boolean works(String language, String name) {
        List<String> command = new ArrayList<>();
        command.add(name);
        command.addAll(PROBE.get(language));
        try {
            Process probe = new ProcessBuilder(command).redirectErrorStream(true).start();
            probe.getOutputStream().close();
            // 探测输出只有一个字符，装不满管道，先等退出再读不会死锁
            if (!probe.waitFor(5, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return false;
            }
            String out = new String(probe.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return probe.exitValue() == 0 && out.strip().contains("1");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Path which(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        String[] exts = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new String[]{".exe", ".cmd", ".bat", ""} : new String[]{""};
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : exts) {
                Path candidate = Path.of(dir, name + ext);
                if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String clip(String text) {
        if (text.length() <= Coding.MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, Coding.MAX_OUTPUT_CHARS)
                + "\n…输出超过 " + Coding.MAX_OUTPUT_CHARS + " 字符，已截断";
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清不掉不影响结果
                }
            });
        } catch (IOException e) {
            log.debug("清理临时目录失败: {}", dir);
        }
    }

    /** 后台读干一个流。子进程的管道填满会让它阻塞在写上，必须并发读。 */
    private static final class StreamPump {

        private final Thread thread;
        private final StringBuilder buffer = new StringBuilder();

        private StreamPump(java.io.InputStream in, String name) {
            this.thread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] chunk = new char[4096];
                    int n;
                    while ((n = reader.read(chunk)) >= 0) {
                        synchronized (buffer) {
                            buffer.append(chunk, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被强杀时正常发生
                }
            }, name);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static StreamPump start(java.io.InputStream in, String name) {
            return new StreamPump(in, name);
        }

        String join() {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (buffer) {
                return buffer.toString();
            }
        }
    }
}
