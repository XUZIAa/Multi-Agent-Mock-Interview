package com.interviewer.data;

import com.interviewer.core.AppPaths;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本机单文件 SQLite。
 *
 * <p>PRAGMA 走 JDBC URL 参数而不是连接初始化 SQL：Hikari 的 connectionInitSql 只能带一条
 * 语句，而这里有六条，且每条新连接都必须设上（PRAGMA 是连接级的）。
 *
 * <p>WAL 允许多读一写，所以连接池不用压到 1；配合 busy_timeout 让偶发的写冲突自己等过去，
 * 而不是抛 SQLITE_BUSY。
 */
@Configuration
public class SqliteConfig {

    private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

    /** 与 Python 版的 _PRAGMAS 逐项一致。 */
    private static final String PRAGMAS = String.join("&",
            "journal_mode=WAL",
            "synchronous=NORMAL",
            "foreign_keys=ON",
            "busy_timeout=8000",
            "cache_size=-32000",
            "temp_store=MEMORY");

    @Bean
    public DataSource dataSource() {
        String path = AppPaths.databaseFile().toAbsolutePath().toString().replace('\\', '/');
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setJdbcUrl("jdbc:sqlite:" + path + "?" + PRAGMAS);
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(1);
        ds.setPoolName("interviewer-sqlite");
        // SQLite 没有网络往返，连接校验用最轻的语句
        ds.setConnectionTestQuery("SELECT 1");
        log.info("数据库就绪: {}", path);
        return ds;
    }

    /**
     * 建表。
     *
     * <p>没用 Boot 的 sql-init：那套要么每次执行要么完全不执行，而这里要的是幂等建表
     * （全是 IF NOT EXISTS），且必须在任何 Mapper 被调用之前跑完。
     */
    @Bean
    public SchemaInitializer schemaInitializer(DataSource dataSource) {
        return new SchemaInitializer(dataSource);
    }

    /** 逐条执行 schema.sql。 */
    public static class SchemaInitializer {

        public SchemaInitializer(DataSource dataSource) {
            String script = read();
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                for (String stmt : script.split(";")) {
                    String sql = stripComments(stmt).strip();
                    if (!sql.isEmpty()) {
                        st.execute(sql);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("建表失败", e);
            }
            log.info("表结构已就绪");
        }

        private static String read() {
            try (InputStream in = SqliteConfig.class.getResourceAsStream("/schema.sql")) {
                if (in == null) {
                    throw new IllegalStateException("找不到 schema.sql");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取 schema.sql 失败", e);
            }
        }

        /** 去掉行注释，避免把注释里的分号当成语句边界之后剩下半截。 */
        private static String stripComments(String sql) {
            StringBuilder sb = new StringBuilder(sql.length());
            for (String line : sql.split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("--")) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }
}
