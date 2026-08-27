package com.interviewer.domain.bank;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.Text;
import com.interviewer.core.type.Depth;
import lombok.Getter;
import lombok.Setter;

/**
 * 单个技能点的推进状态。这是「越来越深还是换领域」的唯一依据。
 *
 * <p>阈值是确定性的：≥0.65 深入一层，0.40~0.65 同层换角度，连续两次 &lt;0.40 就放弃。
 * 放弃不是宽容，是停止无效追问——分数照低记，复盘里会单列出来。
 */
@Getter
@Setter
public class SkillProgress {

    public static final double GOOD_ANSWER = 0.65;
    public static final double WEAK_ANSWER = 0.4;
    public static final int ABANDON_STREAK = 2;

    private String skill = "";
    private String domain = "";
    private int depthReached = 0;
    private int attempts = 0;
    private int attemptsAtDepth = 0;
    private int lowStreak = 0;
    private double bestQuality = 0.0;
    private boolean exhausted = false;
    private int abandonedAtDepth = 0;

    public SkillProgress() {
    }

    public SkillProgress(String skill, String domain) {
        this.skill = skill == null ? "" : skill.strip();
        this.domain = domain == null ? "" : domain.strip();
    }

    @JsonIgnore
    public int nextDepth() {
        return Math.min(Depth.MAX, depthReached + 1);
    }

    /** 记录一次回答并决定下一步。质量未知时按一般水平处理。 */
    public DepthAction observe(Double quality) {
        attempts++;
        attemptsAtDepth++;
        double score = quality == null ? 0.5 : quality;
        bestQuality = Math.max(bestQuality, score);

        if (score >= GOOD_ANSWER) {
            lowStreak = 0;
            if (depthReached >= Depth.MAX) {
                return DepthAction.SWITCH;
            }
            depthReached = Math.min(Depth.MAX, depthReached + 1);
            attemptsAtDepth = 0;
            return DepthAction.DEEPEN;
        }

        if (score >= WEAK_ANSWER) {
            lowStreak = 0;
            return attemptsAtDepth >= 2 ? DepthAction.SWITCH : DepthAction.SIDESTEP;
        }

        lowStreak++;
        if (lowStreak >= ABANDON_STREAK) {
            exhausted = true;
            abandonedAtDepth = Math.max(1, depthReached);
            return DepthAction.ABANDON;
        }
        return DepthAction.SIDESTEP;
    }

    @JsonIgnore
    public String summary() {
        String state = exhausted ? "已放弃深挖" : "最深触及 D" + Math.max(1, depthReached);
        return domain + "/" + skill + "：" + state
                + "，问过 " + attempts + " 次，最佳表现 " + Text.fixed(bestQuality, 2);
    }

    /** 字典键统一小写，避免同一个技能点因大小写分裂成两条推进记录。 */
    public static String key(String skill) {
        return skill == null ? "" : skill.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** 取或建某技能点的推进状态。 */
    public static SkillProgress forSkill(java.util.Map<String, SkillProgress> progress,
                                        String skill, String domain) {
        return progress.computeIfAbsent(key(skill), k -> new SkillProgress(skill, domain));
    }
}
