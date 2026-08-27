package com.interviewer.domain.persona;

/**
 * 0~10 刻度到行为描述的翻译。
 *
 * <p>模型对数字不敏感、对指令敏感：给它「攻击性 9」没用，给它「毫不客气地否定，
 * 甚至流露不耐烦」才有用。所有人设维度都要经过这一层才进提示词。
 */
final class Scale {

    private Scale() {
    }

    /** 四档分桶：0-2 / 3-5 / 6-8 / 9-10。分界点不能动，内置人设的取值是按它调的。 */
    static String of(int value, String low, String mid, String high, String extreme) {
        if (value <= 2) {
            return low;
        }
        if (value <= 5) {
            return mid;
        }
        if (value <= 8) {
            return high;
        }
        return extreme;
    }

    static int level(int value) {
        return Math.max(0, Math.min(10, value));
    }
}
