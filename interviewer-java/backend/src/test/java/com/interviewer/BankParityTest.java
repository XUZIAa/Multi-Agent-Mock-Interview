package com.interviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.interviewer.core.type.QuestionSource;
import com.interviewer.domain.bank.BankQuestion;
import com.interviewer.domain.bank.QuestionBank;
import com.interviewer.domain.bank.SkillProgress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 题库选题与 Python 原版逐值比对。
 *
 * <p>候选集的排序键是四层的（必问 → 偏好领域 → 来源优先级 → 深度），并列时还依赖
 * 题库原序。这里同时验证了深度天花板与「已放弃的技能点不再出题」两条门槛。
 */
class BankParityTest {

    @Test
    void candidateSelectionMatchesPythonBaseline() throws Exception {
        JsonNode root = Parity.load("bank");
        Assumptions.assumeTrue(root != null, "没有 bank 基准文件");

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        List<BankQuestion> questions = new ArrayList<>();
        for (JsonNode q : root.get("questions")) {
            questions.add(mapper.treeToValue(q, BankQuestion.class));
        }
        QuestionBank bank = new QuestionBank(questions);
        assertEquals(54, bank.getQuestions().size(), "题库规模对不上");

        for (JsonNode c : root.get("cases")) {
            String tag = c.get("tag").asText();

            Set<Integer> asked = new LinkedHashSet<>();
            c.get("asked").forEach(n -> asked.add(n.asInt()));

            Map<String, SkillProgress> progress = new LinkedHashMap<>();
            for (JsonNode spec : c.get("progress")) {
                String skill = spec.get(0).asText();
                String domain = spec.get(1).asText();
                SkillProgress st = SkillProgress.forSkill(progress, skill, domain);
                spec.get(2).forEach(q -> st.observe(q.isNull() ? null : q.asDouble()));
            }

            List<QuestionSource> sources = null;
            if (!c.get("sources").isNull()) {
                sources = new ArrayList<>();
                for (JsonNode s : c.get("sources")) {
                    sources.add(QuestionSource.of(s.asText()));
                }
            }
            String prefer = c.get("prefer").isNull() ? null : c.get("prefer").asText();
            List<String> avoid = new ArrayList<>();
            c.get("avoid").forEach(a -> avoid.add(a.asText()));

            List<Integer> wantIds = new ArrayList<>();
            c.get("ids").forEach(n -> wantIds.add(n.asInt()));

            List<Integer> gotIds = bank.candidates(asked, progress, sources, prefer, avoid,
                            c.get("limit").asInt())
                    .stream().map(BankQuestion::id).toList();

            assertEquals(wantIds, gotIds, "候选集不一致: " + tag);
        }
    }
}
