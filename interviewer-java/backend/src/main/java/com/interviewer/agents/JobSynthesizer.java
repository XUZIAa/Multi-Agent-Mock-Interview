package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.JobLevel;
import com.interviewer.domain.company.Companies;
import com.interviewer.domain.company.CompanyProfile;
import com.interviewer.domain.resume.JobDescription;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 只给岗位名称时合成一份贴合市场实际的 JD。
 *
 * <p>合成完要拼回一份可读的 raw_text：下游的差距诊断吃的是原文，不是结构化字段。
 */
@Component
public class JobSynthesizer extends Agent {

    public JobSynthesizer(LlmRouter router) {
        super(router);
    }

    @Override
    protected String role() {
        return Providers.ROLE_ANALYST;
    }

    record Raw(@JsonProperty("company") String company,
               @JsonProperty("title") String title,
               @JsonProperty("must_have") List<String> mustHave,
               @JsonProperty("nice_to_have") List<String> niceToHave,
               @JsonProperty("responsibilities") List<String> responsibilities) {

        Raw(String company, String title, List<String> mustHave, List<String> niceToHave,
            List<String> responsibilities) {
            this.company = Text.safe(company);
            this.title = Text.safe(title);
            this.mustHave = mustHave == null ? List.of() : List.copyOf(mustHave);
            this.niceToHave = niceToHave == null ? List.of() : List.copyOf(niceToHave);
            this.responsibilities = responsibilities == null
                    ? List.of() : List.copyOf(responsibilities);
        }
    }

    public JobDescription synthesize(String title, CompanyTier tier, JobLevel level, String extra) {
        CompanyProfile profile = Companies.of(tier);
        Raw raw = client().structured(
                List.of(new SystemMessage(Prompts.JD_SYNTH),
                        new UserMessage(Prompts.jdSynthUser(title, tier.label(),
                                profile.jdFlavor(), level.label(), extra))),
                Raw.class, 0.45, 2500, 1);

        List<String> mustHave = head(clean(raw.mustHave(), 40), 9);
        List<String> nice = head(clean(raw.niceToHave(), 40), 6);
        List<String> duties = head(clean(raw.responsibilities(), 60), 6);
        String company = raw.company().strip().isEmpty()
                ? "某" + tier.label() : raw.company().strip();
        String resolvedTitle = raw.title().strip().isEmpty()
                ? Text.safe(title).strip() : raw.title().strip();

        List<String> lines = new ArrayList<>();
        lines.add(company + " · " + resolvedTitle + "（" + level.label() + "）");
        lines.add("");
        if (!duties.isEmpty()) {
            lines.add("岗位职责：");
            for (int i = 0; i < duties.size(); i++) {
                lines.add((i + 1) + ". " + duties.get(i));
            }
            lines.add("");
        }
        if (!mustHave.isEmpty()) {
            lines.add("任职要求：");
            for (int i = 0; i < mustHave.size(); i++) {
                lines.add((i + 1) + ". " + mustHave.get(i));
            }
            lines.add("");
        }
        if (!nice.isEmpty()) {
            lines.add("加分项：");
            nice.forEach(n -> lines.add("- " + n));
        }

        JobDescription job = new JobDescription();
        job.setSourceName(resolvedTitle + "（一键生成）");
        job.setRawText(String.join("\n", lines).strip());
        job.setCompany(company);
        job.setTitle(resolvedTitle);
        job.setMustHave(mustHave);
        job.setNiceToHave(nice);
        job.setResponsibilities(duties);
        return job;
    }

    private static List<String> clean(List<String> values, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String text = Text.trim(value, limit);
            String key = text.toLowerCase(Locale.ROOT);
            if (text.isEmpty() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            out.add(text);
        }
        return out;
    }

    private static List<String> head(List<String> list, int limit) {
        return list.size() <= limit ? List.copyOf(list) : List.copyOf(list.subList(0, limit));
    }
}
