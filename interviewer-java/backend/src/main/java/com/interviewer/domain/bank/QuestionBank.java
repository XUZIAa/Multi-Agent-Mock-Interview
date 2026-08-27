package com.interviewer.domain.bank;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.type.QuestionSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面试前一次性生成，面试中只读。导演只能从这里挑新问题。
 */
public class QuestionBank {

    private List<BankQuestion> questions = new ArrayList<>();

    public QuestionBank() {
    }

    public QuestionBank(List<BankQuestion> questions) {
        setQuestions(questions);
    }

    public List<BankQuestion> getQuestions() {
        return questions;
    }

    public void setQuestions(List<BankQuestion> value) {
        this.questions = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    @JsonIgnore
    public BankQuestion byId(int questionId) {
        return questions.stream().filter(q -> q.id() == questionId).findFirst().orElse(null);
    }

    /** 按题库顺序去重后的领域清单。导演靠它判断「还有哪些方向没碰过」。 */
    @JsonIgnore
    public List<String> domains() {
        Set<String> seen = new LinkedHashSet<>();
        questions.forEach(q -> {
            if (!q.domain().isEmpty()) {
                seen.add(q.domain());
            }
        });
        return List.copyOf(seen);
    }

    @JsonIgnore
    public List<String> skills() {
        Set<String> seen = new LinkedHashSet<>();
        questions.forEach(q -> {
            if (!q.skill().isEmpty()) {
                seen.add(q.skill());
            }
        });
        return List.copyOf(seen);
    }

    @JsonIgnore
    public List<BankQuestion> pendingMustAsk(Set<Integer> askedIds) {
        return questions.stream()
                .filter(q -> q.mustAsk() && !askedIds.contains(q.id()))
                .toList();
    }

    /**
     * 挑出当前可问的新题。
     *
     * <p>四道门槛：问过的排除、来源不符的排除、技能点已放弃的排除、深度超过该技能点
     * 「下一层」的排除。最后一条是深度阶梯的另一半——没答好就不许跳到更深的题。
     *
     * <p>排序键：必问优先 → 命中偏好领域 → 来源优先级 → 深度。并列时保持题库原序，
     * 这样同一状态下选出的候选集是稳定的。
     */
    @JsonIgnore
    public List<BankQuestion> candidates(Set<Integer> askedIds,
                                        Map<String, SkillProgress> progress,
                                        Collection<QuestionSource> sources,
                                        String preferDomain,
                                        Collection<String> avoidDomains,
                                        int limit) {
        List<BankQuestion> pool = new ArrayList<>();
        for (BankQuestion q : questions) {
            if (askedIds.contains(q.id())) {
                continue;
            }
            if (sources != null && !sources.contains(q.source())) {
                continue;
            }
            SkillProgress state = progress.get(SkillProgress.key(q.skill()));
            if (state != null && state.isExhausted()) {
                continue;
            }
            int ceiling = state != null ? state.nextDepth() : 1;
            if (q.depth() > Math.max(1, ceiling)) {
                continue;
            }
            if (avoidDomains != null && avoidDomains.contains(q.domain())
                    && !q.domain().equals(preferDomain)) {
                continue;
            }
            pool.add(q);
        }

        pool.sort(Comparator
                .comparingInt((BankQuestion q) -> q.mustAsk() ? 0 : 1)
                .thenComparingInt(q -> (preferDomain != null && q.domain().equals(preferDomain)) ? 0 : 1)
                .thenComparingInt(q -> q.source().priority())
                .thenComparingInt(BankQuestion::depth));
        return pool.size() <= limit ? List.copyOf(pool) : List.copyOf(pool.subList(0, limit));
    }

    @JsonIgnore
    public int remainingCount(Set<Integer> askedIds) {
        return (int) questions.stream().filter(q -> !askedIds.contains(q.id())).count();
    }

    @JsonIgnore
    public String coverageLine(Set<Integer> askedIds) {
        List<BankQuestion> pending = pendingMustAsk(askedIds);
        if (pending.isEmpty()) {
            return "JD 必问项已全部覆盖";
        }
        String skills = pending.stream()
                .limit(6)
                .map(BankQuestion::skill)
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        return "尚未覆盖的 JD 必问项：" + skills;
    }
}
