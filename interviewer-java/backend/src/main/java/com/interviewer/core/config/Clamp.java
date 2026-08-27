package com.interviewer.core.config;

/**
 * 配置项的范围钳制。
 *
 * <p>越界一律夹回边界，绝不让一个字段把整份配置搞成不可用：这份配置里既有用户
 * 在界面上调的值，也有运行时学到的值（麦克风增益），历史值越界很正常。
 * 前端的滑块本身就限制在同样的区间，所以钳制对用户不可见。
 */
final class Clamp {

    private Clamp() {
    }

    static double of(double value, double low, double high) {
        if (Double.isNaN(value)) {
            return low;
        }
        return Math.max(low, Math.min(high, value));
    }

    static int of(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
