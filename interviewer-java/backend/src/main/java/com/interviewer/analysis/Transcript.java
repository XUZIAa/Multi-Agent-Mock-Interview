package com.interviewer.analysis;

import com.interviewer.core.Text;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.QuestionRecord;
import com.interviewer.domain.interview.TurnRecord;
import java.util.ArrayList;
import java.util.List;

/** 逐字稿格式化。 */
public final class Transcript {

    private Transcript() {
    }

    /**
     * 带轮次号的逐字稿。
     *
     * <p>轮次号是批注的锚点：复盘模型按 {@code #N} 引用轮次，前端再拿这个号去查原文。
     * 格式改了批注就锚不回去。
     */
    public static String formatTurns(List<TurnRecord> turns) {
        List<String> lines = new ArrayList<>();
        for (TurnRecord turn : turns) {
            if (!Text.notBlank(turn.getText())) {
                continue;
            }
            String mark = turn.isWasInterrupted() ? "（被打断）" : "";
            lines.add("[#" + turn.getIndex() + " " + Text.mmss(turn.getStartedAtMs()) + " "
                    + turn.getSpeaker().label() + "]" + mark + " " + turn.getText());
        }
        return String.join("\n", lines);
    }

    public static String formatQuestions(List<QuestionRecord> questions) {
        List<String> lines = new ArrayList<>();
        for (QuestionRecord q : questions) {
            if (!Text.notBlank(q.getSpokenText()) && !Text.notBlank(q.getBrief())) {
                continue;
            }
            String asked = Text.notBlank(q.getSpokenText()) ? q.getSpokenText() : q.getBrief();
            String answer = Text.notBlank(q.getAnswerText())
                    ? q.getAnswerText().strip() : "（未作答）";
            lines.add("[Q" + q.getIndex() + " " + q.getPhase().label() + "] 问：" + asked + "\n"
                    + "        答：" + answer);
        }
        return String.join("\n", lines);
    }

    public static String codingSummary(InterviewState state) {
        if (!Text.notBlank(state.getCodeSnapshot())) {
            return "";
        }
        String lang = state.getCodeLanguage();
        return "语言：" + lang + "\n代码：\n```" + lang + "\n"
                + Text.cut(state.getCodeSnapshot(), 6000) + "\n```";
    }
}
