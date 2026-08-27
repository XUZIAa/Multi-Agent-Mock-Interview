package com.interviewer.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 文本与数值格式化。
 *
 * <p>全部走 {@link Locale#ROOT}：本机是 zh_CN，默认 locale 下 %.2f 会输出逗号小数点，
 * 那些字符串要么进提示词、要么进界面，一个逗号就能把数字读成两个。
 */
public final class Text {

    private Text() {
    }

    /**
     * 与 Locale 无关的格式化。
     *
     * <p>浮点定点数不要用它的 %f：Java 的 Formatter 是 HALF_UP，与 Python 的
     * round-half-even 不一致，0.125 会一个给 0.13 一个给 0.12。走 {@link #fixed}。
     */
    public static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    /**
     * 定点小数，语义等同 Python 的 {@code f"{x:.Nf}"}。
     *
     * <p>用 {@code new BigDecimal(double)} 取二进制精确值再 HALF_EVEN，与 Python 完全一致。
     * 不能用 BigDecimal.valueOf 或 DecimalFormat——它们走 double 的最短十进制表示，
     * 0.755 会被当成正好的中点而进位成 0.76，Python 给的是 0.75。
     *
     * <p>这些数字会进提示词（评分历史、质量分），差一位就是给模型的另一个信号。
     */
    public static String fixed(double value, int scale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fmt("%.0f", 0.0);
        }
        return new BigDecimal(value).setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Python 的 %g 默认有效位数。 */
    private static final int G_PRECISION = 6;

    /**
     * 通用数字格式，语义等同 Python 的 {@code f"{x:g}"}（6 位有效数字、去尾零、必要时用指数）。
     *
     * <p>Java 的 {@code %g} 语义不同：它固定补齐到 6 位有效数字，3.0 会输出 3.00000。
     * 这个格式用在把模型给的数字压成文本（经验年限之类），多出来的零会被读成精度。
     */
    public static String gFormat(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        if (value == 0.0) {
            // 负零在 Python 里输出 -0，保持一致
            return (1 / value < 0) ? "-0" : "0";
        }
        BigDecimal rounded = new BigDecimal(value)
                .round(new MathContext(G_PRECISION, RoundingMode.HALF_EVEN))
                .stripTrailingZeros();
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (exponent < -4 || exponent >= G_PRECISION) {
            String mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString();
            int abs = Math.abs(exponent);
            return mantissa + "e" + (exponent < 0 ? "-" : "+") + (abs < 10 ? "0" + abs : abs);
        }
        return rounded.toPlainString();
    }

    /** 毫秒转 mm:ss。 */
    public static String mmss(long ms) {
        long total = Math.max(0, ms) / 1000;
        return fmt("%02d:%02d", total / 60, total % 60);
    }

    /**
     * 压成一行并限长。超长时末尾换成省略号，保持总长不超过 limit。
     *
     * <p>模型返回的字段经常带换行和多余空白，直接塞进提示词会破坏结构。
     */
    public static String trim(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        if (flat.length() <= limit) {
            return flat;
        }
        return flat.substring(0, Math.max(0, limit - 1)) + "…";
    }

    /** 按字符数硬截断，不加省略号。用于喂给模型的长文本。 */
    public static String cut(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public static double clamp(double value, double low, double high) {
        if (Double.isNaN(value)) {
            return low;
        }
        return Math.max(low, Math.min(high, value));
    }

    /** 0~1 的钳制，模型给的质量分与置信度都用它。 */
    public static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    public static String safe(String text) {
        return text == null ? "" : text;
    }

    public static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
