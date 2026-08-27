package com.interviewer.core;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 用户数据根目录，随平台落到标准位置。
 *
 * <p>日志目录要在 Spring 上下文之前就位（logback 靠系统属性拿它），所以这里
 * 全是静态方法，不进容器。
 */
public final class AppPaths {

    public static final String APP_DIR_NAME = "Interviewer";
    /** logback-spring.xml 通过这个属性拿日志目录 */
    public static final String LOG_DIR_PROPERTY = "interviewer.logDir";

    private static final Path DATA_ROOT = resolveRoot();

    private AppPaths() {
    }

    /**
     * 数据根目录的覆盖点。
     *
     * <p>测试必须靠它指到临时目录——否则跑一次测试就把用户真实的面试记录写脏了。
     * 生产上也留着：外壳想把数据放到别处时不必改代码。
     */
    public static final String DATA_ROOT_PROPERTY = "interviewer.dataRoot";

    private static Path resolveRoot() {
        String override = System.getProperty(DATA_ROOT_PROPERTY);
        if (override != null && !override.isBlank()) {
            return ensure(Paths.get(override.strip()));
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        Path base;
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            base = (local == null || local.isBlank())
                    ? userHome().resolve("AppData").resolve("Local")
                    : Paths.get(local);
        } else if (os.contains("mac")) {
            base = userHome().resolve("Library").resolve("Application Support");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            base = (xdg == null || xdg.isBlank())
                    ? userHome().resolve(".local").resolve("share")
                    : Paths.get(xdg);
        }
        return ensure(base.resolve(APP_DIR_NAME));
    }

    private static Path userHome() {
        return Paths.get(System.getProperty("user.home"));
    }

    private static Path ensure(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建目录: " + path, e);
        }
    }

    public static Path dataRoot() {
        return DATA_ROOT;
    }

    public static Path configFile() {
        return DATA_ROOT.resolve("config.json");
    }

    public static Path databaseFile() {
        return DATA_ROOT.resolve("interviewer.db");
    }

    public static Path logDir() {
        return ensure(DATA_ROOT.resolve("logs"));
    }

    public static Path audioDir() {
        return ensure(DATA_ROOT.resolve("audio"));
    }

    public static Path exportDir() {
        return ensure(DATA_ROOT.resolve("exports"));
    }

    public static Path resumeDir() {
        return ensure(DATA_ROOT.resolve("resumes"));
    }
}
