package com.interviewer.agents;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.GapSeverity;
import com.interviewer.core.type.InterviewPhase;
import com.interviewer.core.type.Labeled;
import com.interviewer.domain.resume.GapReport;
import com.interviewer.domain.resume.JobDescription;
import com.interviewer.domain.resume.ProjectHighlight;
import com.interviewer.domain.resume.ResumeProfile;
import com.interviewer.domain.resume.SkillGap;
import com.interviewer.domain.resume.SkillMatch;
import com.interviewer.llm.LlmRouter;
import com.interviewer.llm.Prompts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 面试前的资料理解与差距诊断。走强模型，不计延迟。
 *
 * <p>这里的别名最多：模型把 skill 写成 requirement、把 stack 写成 tech_stack、把
 * summary 写成 description 的情况都真实发生过。每一个别名都是被真实输出教出来的。
 */
@Component
public class ResumeAgent extends Agent {

    /** 只认这五个阶段：其余阶段的时长是固定占比，加权没有意义。 */
    private static final Map<String, InterviewPhase> PHASE_KEYS = Map.of(
            "resume_deep_dive", InterviewPhase.RESUME_DEEP_DIVE,
            "tech_depth", InterviewPhase.TECH_DEPTH,
            "behavioral", InterviewPhase.BEHAVIORAL,
            "coding", InterviewPhase.CODING,
            "stress", InterviewPhase.STRESS);

    public ResumeAgent(LlmRouter router) {
        super(router);
    }

    @Override
    protected String role() {
        return Providers.ROLE_ANALYST;
    }

    // ---------- 模型原始输出 ----------

    record ProjectRaw(@JsonProperty("name") @JsonAlias({"title", "project"}) String name,
                      @JsonProperty("role") String role,
                      @JsonProperty("stack") @JsonAlias({"tech_stack", "technologies"})
                      List<String> stack,
                      @JsonProperty("impact") @JsonAlias({"result", "outcome"}) String impact,
                      @JsonProperty("summary") @JsonAlias({"description", "desc", "detail"})
                      String summary) {

        ProjectRaw(String name, String role, List<String> stack, String impact, String summary) {
            this.name = Text.safe(name);
            this.role = Text.safe(role);
            this.stack = stack == null ? List.of() : List.copyOf(stack);
            this.impact = Text.safe(impact);
            this.summary = Text.safe(summary);
        }

        /** 无名项目留着没用：面试官点名项目时说不出名字。 */
        ProjectHighlight toDomain() {
            String cleaned = name.strip();
            if (cleaned.isEmpty()) {
                return null;
            }
            return new ProjectHighlight(cleaned, role,
                    stack.size() <= 10 ? stack : stack.subList(0, 10), impact, summary);
        }
    }

    record ResumeRaw(@JsonProperty("candidate_name") @JsonAlias({"name", "candidate"})
                     String candidateName,
                     @JsonProperty("years_of_experience")
                     @JsonAlias({"years", "experience_years"}) double yearsOfExperience,
                     @JsonProperty("current_title") @JsonAlias({"title", "position"})
                     String currentTitle,
                     @JsonProperty("education") String education,
                     @JsonProperty("skills") List<String> skills,
                     @JsonProperty("self_claims") @JsonAlias({"claims", "highlights"})
                     List<String> selfClaims,
                     @JsonProperty("projects") List<ProjectRaw> projects) {

        ResumeRaw(String candidateName, double yearsOfExperience, String currentTitle,
                  String education, List<String> skills, List<String> selfClaims,
                  List<ProjectRaw> projects) {
            this.candidateName = Text.safe(candidateName);
            this.yearsOfExperience = yearsOfExperience;
            this.currentTitle = Text.safe(currentTitle);
            this.education = Text.safe(education);
            this.skills = skills == null ? List.of() : List.copyOf(skills);
            this.selfClaims = selfClaims == null ? List.of() : List.copyOf(selfClaims);
            this.projects = projects == null ? List.of() : List.copyOf(projects);
        }
    }

    record JobRaw(@JsonProperty("company") String company,
                  @JsonProperty("title") String title,
                  @JsonProperty("must_have") @JsonAlias({"requirements", "required"})
                  List<String> mustHave,
                  @JsonProperty("nice_to_have") @JsonAlias({"preferred", "bonus"})
                  List<String> niceToHave,
                  @JsonProperty("responsibilities") @JsonAlias({"duties"})
                  List<String> responsibilities) {

        JobRaw(String company, String title, List<String> mustHave, List<String> niceToHave,
               List<String> responsibilities) {
            this.company = Text.safe(company);
            this.title = Text.safe(title);
            this.mustHave = mustHave == null ? List.of() : List.copyOf(mustHave);
            this.niceToHave = niceToHave == null ? List.of() : List.copyOf(niceToHave);
            this.responsibilities = responsibilities == null
                    ? List.of() : List.copyOf(responsibilities);
        }
    }

    record MatchRaw(@JsonProperty("skill")
                    @JsonAlias({"requirement", "name", "item", "point", "keyword"}) String skill,
                    @JsonProperty("evidence") @JsonAlias({"proof", "reason"}) String evidence,
                    @JsonProperty("strength") int strength) {

        MatchRaw(String skill, String evidence, int strength) {
            this.skill = Text.safe(skill);
            this.evidence = Text.safe(evidence);
            this.strength = strength;
        }

        SkillMatch toDomain() {
            String cleaned = skill.strip();
            return cleaned.isEmpty() ? null : new SkillMatch(cleaned, evidence, strength);
        }
    }

    record GapItemRaw(@JsonProperty("skill")
                      @JsonAlias({"requirement", "name", "item", "point", "keyword"}) String skill,
                      @JsonProperty("severity") String severity,
                      @JsonProperty("jd_requirement") @JsonAlias({"requirement", "jd"})
                      String jdRequirement,
                      @JsonProperty("why_gap") @JsonAlias({"reason", "why"}) String whyGap,
                      @JsonProperty("bridge_asset") @JsonAlias({"bridge", "asset"})
                      String bridgeAsset,
                      @JsonProperty("talking_script") @JsonAlias({"script", "talking_point"})
                      String talkingScript,
                      @JsonProperty("study_hint") @JsonAlias({"hint", "study"}) String studyHint) {

        GapItemRaw(String skill, String severity, String jdRequirement, String whyGap,
                   String bridgeAsset, String talkingScript, String studyHint) {
            this.skill = Text.safe(skill);
            this.severity = Text.safe(severity).isEmpty() ? "major" : Text.safe(severity);
            this.jdRequirement = Text.safe(jdRequirement);
            this.whyGap = Text.safe(whyGap);
            this.bridgeAsset = Text.safe(bridgeAsset);
            this.talkingScript = Text.safe(talkingScript);
            this.studyHint = Text.safe(studyHint);
        }
    }

    record GapRaw(@JsonProperty("match_score") @JsonAlias({"score"}) int matchScore,
                  @JsonProperty("verdict") @JsonAlias({"conclusion", "summary"}) String verdict,
                  @JsonProperty("matches") List<MatchRaw> matches,
                  @JsonProperty("gaps") List<GapItemRaw> gaps,
                  @JsonProperty("predicted_questions") @JsonAlias({"questions"})
                  List<String> predictedQuestions,
                  @JsonProperty("focus_skills") @JsonAlias({"focus"}) List<String> focusSkills,
                  @JsonProperty("phase_emphasis") Map<String, Integer> phaseEmphasis) {

        GapRaw(int matchScore, String verdict, List<MatchRaw> matches, List<GapItemRaw> gaps,
               List<String> predictedQuestions, List<String> focusSkills,
               Map<String, Integer> phaseEmphasis) {
            this.matchScore = matchScore;
            this.verdict = Text.safe(verdict);
            this.matches = matches == null ? List.of() : List.copyOf(matches);
            this.gaps = gaps == null ? List.of() : List.copyOf(gaps);
            this.predictedQuestions = predictedQuestions == null
                    ? List.of() : List.copyOf(predictedQuestions);
            this.focusSkills = focusSkills == null ? List.of() : List.copyOf(focusSkills);
            this.phaseEmphasis = phaseEmphasis == null ? Map.of() : Map.copyOf(phaseEmphasis);
        }
    }

    // ---------- 对外接口 ----------

    public ResumeProfile parseResume(String rawText, String sourceName) {
        ResumeRaw parsed = client().structured(
                List.of(new SystemMessage(Prompts.RESUME_EXTRACT),
                        new UserMessage(Text.cut(rawText, 20000))),
                ResumeRaw.class, 0.1, 4096, 1);

        ResumeProfile profile = new ResumeProfile();
        profile.setSourceName(Text.safe(sourceName));
        // 原文必须整份留着：差距诊断与满分答案重构都要拿它当素材，摘要不够用
        profile.setRawText(rawText);
        profile.setCandidateName(parsed.candidateName().strip());
        profile.setYearsOfExperience(Math.max(0.0, parsed.yearsOfExperience()));
        profile.setCurrentTitle(parsed.currentTitle().strip());
        profile.setSkills(head(unique(parsed.skills()), 40));
        profile.setEducation(parsed.education().strip());
        profile.setSelfClaims(head(nonBlank(parsed.selfClaims()), 10));

        List<ProjectHighlight> projects = new ArrayList<>();
        for (ProjectRaw raw : parsed.projects()) {
            ProjectHighlight project = raw.toDomain();
            if (project != null && projects.size() < 6) {
                projects.add(project);
            }
        }
        profile.setProjects(projects);
        return profile;
    }

    public JobDescription parseJob(String rawText, String sourceName) {
        JobRaw parsed = client().structured(
                List.of(new SystemMessage(Prompts.JD_EXTRACT),
                        new UserMessage(Text.cut(rawText, 12000))),
                JobRaw.class, 0.1, 2000, 1);

        JobDescription job = new JobDescription();
        job.setSourceName(Text.safe(sourceName));
        job.setRawText(rawText);
        job.setCompany(parsed.company().strip());
        job.setTitle(parsed.title().strip());
        job.setMustHave(head(unique(parsed.mustHave()), 25));
        job.setNiceToHave(head(unique(parsed.niceToHave()), 20));
        job.setResponsibilities(head(nonBlank(parsed.responsibilities()), 12));
        return job;
    }

    public GapReport analyzeGap(ResumeProfile resume, JobDescription job) {
        GapRaw parsed = client().structured(
                List.of(new SystemMessage(Prompts.GAP_ANALYSIS),
                        new UserMessage(Prompts.gapUser(resume.getRawText(), job.getRawText()))),
                GapRaw.class, 0.35, 6000, 1);

        List<SkillGap> gaps = new ArrayList<>();
        for (GapItemRaw item : parsed.gaps()) {
            if (!Text.notBlank(item.skill())) {
                continue;
            }
            gaps.add(new SkillGap(item.skill().strip(),
                    Labeled.parse(GapSeverity.class, item.severity(), GapSeverity.MAJOR),
                    item.jdRequirement().strip(), item.whyGap().strip(),
                    item.bridgeAsset().strip(), item.talkingScript().strip(),
                    item.studyHint().strip()));
        }
        gaps.sort(Comparator.comparingInt(g -> g.severity().order()));

        List<SkillMatch> matches = new ArrayList<>();
        for (MatchRaw raw : parsed.matches()) {
            SkillMatch match = raw.toDomain();
            if (match != null && matches.size() < 20) {
                matches.add(match);
            }
        }

        Map<InterviewPhase, Integer> emphasis = new EnumMap<>(InterviewPhase.class);
        parsed.phaseEmphasis().forEach((key, value) -> {
            InterviewPhase phase = PHASE_KEYS.get(Text.safe(key).strip().toLowerCase(Locale.ROOT));
            if (phase != null && value != null) {
                emphasis.put(phase, Math.max(0, Math.min(5, value)));
            }
        });

        // 模型没给必考清单时，从缺口与匹配项里凑一份出来——题库要靠它标必问
        List<String> focus = head(unique(parsed.focusSkills()), 12);
        if (focus.isEmpty()) {
            List<String> fallback = new ArrayList<>();
            gaps.forEach(g -> fallback.add(g.skill()));
            matches.forEach(m -> fallback.add(m.skill()));
            focus = head(unique(fallback), 10);
        }

        GapReport report = new GapReport();
        report.setMatchScore(parsed.matchScore());
        report.setVerdict(parsed.verdict().strip());
        report.setMatches(matches);
        report.setGaps(head(gaps, 12));
        report.setPredictedQuestions(head(nonBlank(parsed.predictedQuestions()), 10));
        report.setFocusSkills(focus);
        report.setPhaseEmphasis(emphasis);
        return report;
    }

    // ---------- 辅助 ----------

    /** 大小写不敏感去重但保留原文写法：Kubernetes 与 kubernetes 是一个技能。 */
    private static List<String> unique(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String text = Text.safe(value).strip();
            String key = text.toLowerCase(Locale.ROOT);
            if (text.isEmpty() || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            out.add(text);
        }
        return out;
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> out = new ArrayList<>();
        values.forEach(v -> {
            if (Text.notBlank(v)) {
                out.add(v.strip());
            }
        });
        return out;
    }

    private static <T> List<T> head(List<T> list, int limit) {
        return list.size() <= limit ? List.copyOf(list) : List.copyOf(list.subList(0, limit));
    }
}
