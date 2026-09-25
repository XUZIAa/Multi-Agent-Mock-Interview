package com.interviewer.core.type;

import java.util.Map;

/**
 * 深度阶梯。加深还是换领域，由这四级决定，不交给模型自由裁量。
 */
public final class Depth {

    public static final int MAX = 4;

    public static final Map<Integer, String> LABELS = Map.of(
            1, "概念层（是什么、用过没有）",
            2, "实践层（你们怎么做的、具体怎么落地）",
            3, "原理层（为什么这样选、底层如何实现、有何权衡）",
            4, "边界层（极限场景、故障、规模放大十倍）");

    private Depth() {
    }

    public static int clamp(int depth) {
        return Math.max(1, Math.min(MAX, depth));
    }

    public static String label(int depth) {
        return LABELS.getOrDefault(clamp(depth), "");
    }
}
