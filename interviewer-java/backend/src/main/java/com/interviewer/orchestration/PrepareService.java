package com.interviewer.orchestration;

import com.interviewer.agents.BankBuilder;
import com.interviewer.agents.JobSynthesizer;
import com.interviewer.agents.ResumeAgent;
import com.interviewer.core.AppPaths;
import com.interviewer.core.Text;
import com.interviewer.core.error.ConfigException;
import com.interviewer.core.provider.Providers;
import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.JobLevel;
import com.interviewer.data.dto.StoredGap;
import com.interviewer.data.dto.StoredJob;
import com.interviewer.data.dto.StoredResume;
import com.interviewer.data.repository.LibraryRepository;
import com.interviewer.data.repository.PersonaRepository;
import com.interviewer.data.repository.SessionRepository;
import com.interviewer.domain.bank.QuestionBank;
import com.interviewer.domain.interview.InterviewPlan;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.domain.interview.Plans;
import com.interviewer.domain.persona.PersonaContract;
import com.interviewer.domain.resume.GapReport;
import com.interviewer.domain.resume.JobDescription;
import com.interviewer.domain.resume.ResumeProfile;
import com.interviewer.ingest.DocumentReader;
import com.interviewer.llm.LlmRouter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 面试前的资料处理与开场准备。所有耗时步骤都上报进度。 */
@Service
public class PrepareService {

    private static final Logger log = LoggerFactory.getLogger(PrepareService.class);

    /** 进度回调。出题要几十秒，进度塞不进 HTTP 响应，只能另路回推。 */
    public interface Progress {
        void report(String stage, int percent);
    }

    private static final Progress NOOP = (stage, percent) -> {
    };

    private final LlmRouter router;
    private final ResumeAgent resumeAgent;
    private final JobSynthesizer jobSynth;
    private final BankBuilder bankBuilder;
    private final DocumentReader documents;
    private final LibraryRepository library;
    private final SessionRepository sessions;
    private final PersonaRepository personas;

    public PrepareService(LlmRouter router, ResumeAgent resumeAgent, JobSynthesizer jobSynth,
                          BankBuilder bankBuilder, DocumentReader documents,
                          LibraryRepository library, SessionRepository sessions,
                          PersonaRepository personas) {
        this.router = router;
        this.resumeAgent = resumeAgent;
        this.jobSynth = jobSynth;
        this.bankBuilder = bankBuilder;
        this.documents = documents;
        this.library = library;
        this.sessions = sessions;
        this.personas = personas;
    }

    // ---------- 资料摄取 ----------

    public StoredResume ingestResume(Path path, Progress progress) {
        Progress on = progress == null ? NOOP : progress;
        on.report("正在读取简历文件", 10);
        String raw = documents.read(path);
        // 拷一份到数据目录：用户可能把原文件删掉或移走，而复盘时还要用原文
        Path stored = copyIntoLibrary(path);
        on.report("正在结构化解析简历", 40);
        ResumeProfile profile = resumeAgent.parseResume(raw, path.getFileName().toString());
        on.report("正在保存", 85);
        StoredResume result = library.saveResume(profile, stored.toString());
        on.report("简历已就绪", 100);
        return result;
    }

    private static Path copyIntoLibrary(Path path) {
        Path target = AppPaths.resumeDir().resolve(path.getFileName().toString());
        try {
            if (!path.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            log.warn("简历副本保存失败，改用原路径: {}", e.getMessage());
            return path;
        }
    }

    public StoredJob ingestJobFile(Path path, Progress progress) {
        Progress on = progress == null ? NOOP : progress;
        on.report("正在读取 JD 文件", 15);
        String raw = documents.read(path);
        on.report("正在结构化解析 JD", 50);
        JobDescription jd = resumeAgent.parseJob(raw, path.getFileName().toString());
        on.report("正在保存", 85);
        StoredJob result = library.saveJob(jd);
        on.report("岗位已就绪", 100);
        return result;
    }

    public StoredJob ingestJobText(String raw, Progress progress) {
        Progress on = progress == null ? NOOP : progress;
        on.report("正在结构化解析 JD", 40);
        JobDescription jd = resumeAgent.parseJob(raw, "手动粘贴");
        StoredJob result = library.saveJob(jd);
        on.report("岗位已就绪", 100);
        return result;
    }

    /** 只给岗位名称时，按公司类型合成一份贴合市场的 JD。 */
    public StoredJob synthesizeJob(String title, CompanyTier tier, JobLevel level, String extra,
                                   Progress progress) {
        Progress on = progress == null ? NOOP : progress;
        on.report("正在生成岗位描述", 30);
        JobDescription jd = jobSynth.synthesize(title, tier, level, extra);
        on.report("正在保存", 85);
        StoredJob result = library.saveJob(jd);
        on.report("岗位已就绪", 100);
        return result;
    }

    // ---------- 差距诊断 ----------

    public StoredGap diagnose(int resumeId, int jobId, boolean refresh, Progress progress) {
        Progress on = progress == null ? NOOP : progress;
        if (!refresh) {
            StoredGap cached = library.findGap(resumeId, jobId);
            if (cached != null) {
                on.report("已加载上次诊断结果", 100);
                return cached;
            }
        }
        StoredResume resume = library.getResume(resumeId);
        StoredJob job = library.getJob(jobId);
        if (resume == null || job == null) {
            throw new ConfigException("简历或岗位不存在", "选中的简历或岗位已不存在，请重新选择");
        }
        on.report("正在逐条比对 JD 与简历", 35);
        GapReport report = resumeAgent.analyzeGap(resume.profile(), job.job());
        on.report("正在保存诊断结果", 85);
        StoredGap result = library.saveGap(resumeId, jobId, report);
        on.report("诊断完成", 100);
        return result;
    }

    // ---------- 开场准备 ----------

    public InterviewState buildSession(PersonaContract persona, Integer resumeId, Integer jobId,
                                       CompanyTier tier, JobLevel level, int minutes,
                                       boolean codingEnabled, Progress progress) {
        Progress on = progress == null ? NOOP : progress;
        StoredResume resume = resumeId == null ? null : library.getResume(resumeId);
        StoredJob job = jobId == null ? null : library.getJob(jobId);
        StoredGap gap = (resumeId != null && jobId != null)
                ? library.findGap(resumeId, jobId) : null;
        GapReport gapReport = gap == null ? null : gap.report();

        String title = job == null ? "" : job.job().getTitle();
        on.report("正在排定面试流程", 15);
        InterviewPlan plan = Plans.build(persona, minutes, codingEnabled, gapReport);

        QuestionBank bank = new QuestionBank();
        if (resume != null && job != null) {
            on.report("正在基于 JD 与简历生成题库", 35);
            bank = bankBuilder.build(resume.profile(), job.job(), gapReport, tier, level,
                    minutes, codingEnabled, on::report);
        }

        on.report("正在创建面试记录", 80);
        int sessionId = sessions.create(sessionTitle(title, persona.getName(), tier),
                persona, minutes, resumeId, jobId);

        InterviewState state = new InterviewState();
        state.setSessionId(sessionId);
        state.setPersona(persona);
        state.setPlan(plan);
        state.setBank(bank);
        state.setCompanyTier(tier);
        state.setJobLevel(level);
        state.setJobTitle(title);
        state.setResumeDigest(resume == null ? "" : resume.profile().compact());
        state.setJdDigest(job == null ? "" : job.job().compact());
        state.setGapDigest(gapReport == null ? "" : gapReport.compact());
        // 有诊断就按必考清单，没有就退回题库里的技能点——总得有个「还没考到」的清单
        state.setPendingSkills(gapReport != null
                ? new ArrayList<>(gapReport.getFocusSkills())
                : new ArrayList<>(head(bank.skills(), 8)));
        sessions.persistState(state);

        // 趁这里还没有音频链路，把面试中要用的模型连接全部建好。
        // 压到语音启动之后会和音频抢线程，听感就是开场几秒一卡一卡
        on.report("正在预热模型链路", 95);
        router.warm(Providers.ROLE_DIRECTOR, Providers.ROLE_ASSIST, Providers.ROLE_GUARD);

        on.report("准备完成", 100);
        log.info("面试已准备 session={} 题库={} 计划={}分钟",
                sessionId, bank.getQuestions().size(), minutes);
        return state;
    }

    private static String sessionTitle(String jobTitle, String personaName, CompanyTier tier) {
        String head = Text.notBlank(jobTitle) ? jobTitle : "综合面试";
        return head + "｜" + tier.label() + "｜" + personaName;
    }

    private static <T> List<T> head(List<T> list, int limit) {
        return list.size() <= limit ? list : list.subList(0, limit);
    }
}
