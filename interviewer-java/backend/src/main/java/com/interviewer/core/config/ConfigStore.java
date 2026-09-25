package com.interviewer.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 配置读写的唯一入口。明文配置落 JSON，密钥进系统凭据库。
 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile AppSettings settings;

    public ConfigStore(ObjectMapper mapper) {
        this.mapper = mapper;
        this.settings = load();
    }

    public AppSettings settings() {
        return settings;
    }

    /**
     * 配置读不出来绝不能挡住启动：坏文件挪去 .broken 备份，回到默认值继续跑。
     *
     * <p>用户宁愿看到「设置被重置了」，也不愿看到「程序打不开」。
     */
    private AppSettings load() {
        Path file = AppPaths.configFile();
        if (!Files.exists(file)) {
            return new AppSettings();
        }
        try {
            return mapper.readValue(Files.readString(file), AppSettings.class);
        } catch (Exception e) {
            log.warn("配置文件不可用，已回退默认值: {}", e.toString());
            quarantine(file);
            return new AppSettings();
        }
    }

    private static void quarantine(Path file) {
        Path backup = file.resolveSibling(file.getFileName() + ".broken");
        try {
            Files.deleteIfExists(backup);
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("无法备份损坏的配置文件: {}", file);
        }
    }

    /** 落盘。先写临时文件再原子替换，断电时不会留下半份配置。 */
    public void save(AppSettings next) {
        lock.lock();
        try {
            if (next != null) {
                this.settings = next;
            }
            Path file = AppPaths.configFile();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this.settings);
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("保存配置失败", e);
        } finally {
            lock.unlock();
        }
    }

    public void save() {
        save(null);
    }
}
