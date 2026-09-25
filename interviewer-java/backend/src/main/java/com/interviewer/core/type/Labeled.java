package com.interviewer.core.type;

import java.util.Locale;
import java.util.Optional;

/**
 * 带线上值与中文名的枚举。
 *
 * <p>线上值（{@link #value()}）是与前端和模型的契约，不能改；中文名只给人看。
 * Python 版用 StrEnum 把两者合在一起，这里拆开但保持 toString() 等于线上值，
 * 这样 Map 的键、日志与字符串拼接都不会意外落成 Java 的常量名。
 */
public interface Labeled {

    String value();

    String label();

    /** 宽松查找。模型给的大小写和空白都不可信，一律归一化后比对。 */
    static <E extends Enum<E> & Labeled> Optional<E> find(Class<E> type, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.strip().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        for (E item : type.getEnumConstants()) {
            if (item.value().equals(key)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /** 查不到就用兜底值。对应 Python 里那些 try/except ValueError 的写法。 */
    static <E extends Enum<E> & Labeled> E parse(Class<E> type, String raw, E fallback) {
        return find(type, raw).orElse(fallback);
    }
}
