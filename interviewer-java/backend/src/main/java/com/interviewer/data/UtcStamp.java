package com.interviewer.data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * 数据库里的时间戳。
 *
 * <p>格式与 SQLAlchemy 的 SQLite DATETIME 完全一致：{@code yyyy-MM-dd HH:mm:ss.SSSSSS}，
 * 值是 UTC 且不带时区后缀。换格式会读不出用户从 Python 版留下的历史记录。
 *
 * <p>微秒位可能缺省（老库里某些行是整秒），解析时按可选处理。
 */
public final class UtcStamp {

    private static final DateTimeFormatter WRITE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private static final DateTimeFormatter READ = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    private UtcStamp() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : WRITE.format(value);
    }

    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        // 老库里极少数行可能带 T 分隔或时区后缀，一并认下来
        if (text.length() > 10 && text.charAt(10) == 'T') {
            text = text.substring(0, 10) + " " + text.substring(11);
        }
        int plus = text.lastIndexOf('+');
        if (plus > 10) {
            text = text.substring(0, plus);
        }
        if (text.endsWith("Z")) {
            text = text.substring(0, text.length() - 1);
        }
        return LocalDateTime.parse(text, READ);
    }
}
