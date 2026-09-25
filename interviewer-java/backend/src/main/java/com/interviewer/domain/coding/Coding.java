package com.interviewer.domain.coding;

import java.util.List;

/** 代码沙盒的常量。 */
public final class Coding {

    /**
     * 只列真能跑起来的语言。Python 与 JavaScript 都依赖用户机器上的运行时，
     * 取不到就明确报错而不是假装能跑。
     */
    public static final List<String> LANGUAGES = List.of("python", "javascript");

    public static final int MAX_OUTPUT_CHARS = 8000;
    public static final int RUN_TIMEOUT_MS = 6000;

    private Coding() {
    }

    public static boolean supported(String language) {
        return language != null && LANGUAGES.contains(language);
    }
}
