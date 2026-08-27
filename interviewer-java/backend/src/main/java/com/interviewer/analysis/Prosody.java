package com.interviewer.analysis;

import com.interviewer.core.Text;
import com.interviewer.core.type.Speaker;
import com.interviewer.domain.interview.TurnRecord;
import com.interviewer.domain.review.ProsodyReport;
import com.interviewer.domain.review.QuestionProsody;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 副语言指标。全部由规则计算，不交给模型编造。
 *
 * <p>语速、停顿、口头禅密度这些数字是可测的，让模型「感觉」出来只会得到编造值。
 * 算好之后连同「不要改动这些数字」一起喂给复盘模型，它只负责解读。
 */
public final class Prosody {

    private static final Pattern HAN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z'-]*");

    /** 高频填充词。中文口语里这些词本身合法，靠密度而非出现判定问题。 */
    private static final List<String> FILLERS = List.of(
            "嗯", "呃", "唉", "那个", "这个", "然后", "就是说", "就是", "其实",
            "怎么说呢", "你知道", "对吧", "反正", "基本上", "或者说",
            "um", "uh", "like", "you know");

    private static final double FAST_WPM = 315.0;
    private static final double SLOW_WPM = 155.0;
    private static final double FILLER_PER_100 = 5.5;
    private static final double HIGH_PAUSE_RATIO = 0.34;
    private static final long LONG_PAUSE_MS = 4200;

    /** 太短的间隔是正常呼吸，太长的多半是设备问题或离席，都不算思考停顿。 */
    private static final long PAUSE_MIN_MS = 400;
    private static final long PAUSE_MAX_MS = 30000;

    private Prosody() {
    }

    /** 中文按字、英文按词计量，统一成「字/分钟」的分子。 */
    public static int countUnits(String text) {
        int count = 0;
        Matcher han = HAN.matcher(Text.safe(text));
        while (han.find()) {
            count++;
        }
        Matcher latin = LATIN_WORD.matcher(Text.safe(text));
        while (latin.find()) {
            count++;
        }
        return count;
    }

    public static int countFillers(String text) {
        String lowered = Text.safe(text).toLowerCase(Locale.ROOT);
        int total = 0;
        for (String filler : FILLERS) {
            int from = 0;
            while (true) {
                int at = lowered.indexOf(filler, from);
                if (at < 0) {
                    break;
                }
                total++;
                from = at + 1;
            }
        }
        return total;
    }

    public static ProsodyReport analyze(List<TurnRecord> turns, long totalDurationMs) {
        List<TurnRecord> candidate = new ArrayList<>();
        for (TurnRecord t : turns) {
            if (t.getSpeaker() == Speaker.CANDIDATE && Text.notBlank(t.getText())) {
                candidate.add(t);
            }
        }
        if (candidate.isEmpty()) {
            return ProsodyReport.empty("本场没有采集到有效的候选人语音。");
        }

        long speakMs = 0;
        int units = 0;
        int fillers = 0;
        int interrupted = 0;
        for (TurnRecord t : candidate) {
            speakMs += Math.max(0, t.getDurationMs());
            units += countUnits(t.getText());
            fillers += countFillers(t.getText());
            if (t.isWasInterrupted()) {
                interrupted++;
            }
        }

        List<Long> pauses = pauses(candidate);
        long pauseTotal = pauses.stream().mapToLong(Long::longValue).sum();
        long longestPause = pauses.stream().mapToLong(Long::longValue).max().orElse(0);
        long span = speakMs + pauseTotal;

        double wpm = speakMs > 0 ? units / (speakMs / 60000.0) : 0.0;
        // 少于 20 字算不出有意义的密度，给 0 而不是一个夸张的比例
        double fillerRatio = units >= 20 ? fillers / (units / 100.0) : 0.0;
        double pauseRatio = span > 0 ? (double) pauseTotal / span : 0.0;
        double speakingRatio = totalDurationMs > 0 ? (double) speakMs / totalDurationMs : 0.0;

        ProsodyReport report = new ProsodyReport(
                round(wpm, 1), round(fillerRatio, 2), round(pauseRatio, 3),
                longestPause, round(speakingRatio, 3), interrupted,
                perQuestion(candidate), "");
        return report.withVerdict(verdict(report));
    }

    /** 同一个问题内相邻发言段之间的间隔视为思考停顿。 */
    private static List<Long> pauses(List<TurnRecord> candidate) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < candidate.size(); i++) {
            TurnRecord prev = candidate.get(i - 1);
            TurnRecord cur = candidate.get(i);
            if (!java.util.Objects.equals(prev.getQuestionIndex(), cur.getQuestionIndex())) {
                continue;
            }
            long gap = cur.getStartedAtMs() - (prev.getStartedAtMs() + prev.getDurationMs());
            if (gap >= PAUSE_MIN_MS && gap <= PAUSE_MAX_MS) {
                gaps.add(gap);
            }
        }
        return gaps;
    }

    private static List<QuestionProsody> perQuestion(List<TurnRecord> candidate) {
        Map<Integer, List<TurnRecord>> grouped = new TreeMap<>();
        for (TurnRecord turn : candidate) {
            if (turn.getQuestionIndex() != null) {
                grouped.computeIfAbsent(turn.getQuestionIndex(), k -> new ArrayList<>()).add(turn);
            }
        }
        List<QuestionProsody> out = new ArrayList<>();
        grouped.forEach((index, group) -> {
            long speakMs = 0;
            int units = 0;
            int fillers = 0;
            for (TurnRecord t : group) {
                speakMs += Math.max(0, t.getDurationMs());
                units += countUnits(t.getText());
                fillers += countFillers(t.getText());
            }
            List<Long> gaps = pauses(group);
            long gapTotal = gaps.stream().mapToLong(Long::longValue).sum();
            long span = speakMs + gapTotal;
            out.add(new QuestionProsody(index,
                    speakMs > 0 ? round(units / (speakMs / 60000.0), 1) : 0.0,
                    fillers,
                    span > 0 ? round((double) gapTotal / span, 3) : 0.0,
                    gaps.stream().mapToLong(Long::longValue).max().orElse(0)));
        });
        return out;
    }

    private static String verdict(ProsodyReport report) {
        List<String> issues = new ArrayList<>();
        if (report.wordsPerMinute() >= FAST_WPM) {
            issues.add("整体语速偏快（" + Text.fixed(report.wordsPerMinute(), 0)
                    + " 字/分，正常区间 180~300）");
        } else if (report.wordsPerMinute() > 0 && report.wordsPerMinute() <= SLOW_WPM) {
            issues.add("整体语速偏慢（" + Text.fixed(report.wordsPerMinute(), 0)
                    + " 字/分），显得不够自信");
        }
        if (report.fillerRatio() >= FILLER_PER_100) {
            issues.add("口头禅密度偏高（每百字 " + Text.fixed(report.fillerRatio(), 1) + " 次）");
        }
        if (report.pauseRatio() >= HIGH_PAUSE_RATIO) {
            issues.add("思考停顿占比 " + Text.fixed(report.pauseRatio() * 100, 0)
                    + "%，答题过程断续");
        }
        if (report.longestPauseMs() >= LONG_PAUSE_MS) {
            issues.add("最长一次卡顿 " + Text.fixed(report.longestPauseMs() / 1000.0, 1) + " 秒");
        }
        if (report.interruptedCount() >= 2) {
            issues.add("被面试官打断 " + report.interruptedCount() + " 次，说明回答没有先给结论");
        }

        QuestionProsody worst = report.worstQuestion();
        if (worst != null && !issues.isEmpty()) {
            issues.add("问题最集中的是第 " + worst.questionIndex() + " 题");
        }

        if (issues.isEmpty()) {
            return "语速 " + Text.fixed(report.wordsPerMinute(), 0) + " 字/分、停顿占比 "
                    + Text.fixed(report.pauseRatio() * 100, 0) + "%，节奏平稳，表达状态良好。";
        }
        return String.join("；", issues) + "。";
    }

    /** 喂给复盘模型的客观指标，模型不得改动这些数字。 */
    public static String summaryForModel(ProsodyReport report) {
        List<String> lines = new ArrayList<>();
        lines.add("语速：" + Text.fixed(report.wordsPerMinute(), 0) + " 字/分");
        lines.add("口头禅密度：每百字 " + Text.fixed(report.fillerRatio(), 1) + " 次");
        lines.add("思考停顿占比：" + Text.fixed(report.pauseRatio() * 100, 0) + "%");
        lines.add("最长停顿：" + Text.fixed(report.longestPauseMs() / 1000.0, 1) + " 秒");
        lines.add("发言时长占比：" + Text.fixed(report.speakingRatio() * 100, 0) + "%");
        lines.add("被打断次数：" + report.interruptedCount());
        QuestionProsody worst = report.worstQuestion();
        if (!report.perQuestion().isEmpty() && worst != null) {
            lines.add("语速最快的一题：第 " + worst.questionIndex() + " 题（"
                    + Text.fixed(worst.wordsPerMinute(), 0) + " 字/分，停顿占比 "
                    + Text.fixed(worst.pauseRatio() * 100, 0) + "%）");
        }
        return String.join("\n", lines);
    }

    /** Python 的 round() 是 half-even，与 Text.fixed 同源，走它保证一致。 */
    private static double round(double value, int scale) {
        return Double.parseDouble(Text.fixed(value, scale));
    }
}
