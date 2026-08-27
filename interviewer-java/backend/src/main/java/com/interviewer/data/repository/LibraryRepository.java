package com.interviewer.data.repository;

import com.interviewer.core.Text;
import com.interviewer.data.UtcStamp;
import com.interviewer.data.dto.StoredGap;
import com.interviewer.data.dto.StoredJob;
import com.interviewer.data.dto.StoredResume;
import com.interviewer.data.entity.GapReportRow;
import com.interviewer.data.entity.JobRow;
import com.interviewer.data.entity.ResumeRow;
import com.interviewer.data.mapper.GapReportMapper;
import com.interviewer.data.mapper.JobMapper;
import com.interviewer.data.mapper.ResumeMapper;
import com.interviewer.domain.resume.GapReport;
import com.interviewer.domain.resume.JobDescription;
import com.interviewer.domain.resume.ResumeProfile;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 简历、岗位与差距诊断的仓储。 */
@Repository
public class LibraryRepository {

    private final ResumeMapper resumes;
    private final JobMapper jobs;
    private final GapReportMapper gaps;
    private final JsonCodec codec;

    public LibraryRepository(ResumeMapper resumes, JobMapper jobs, GapReportMapper gaps,
                             JsonCodec codec) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.gaps = gaps;
        this.codec = codec;
    }

    // ---------- 简历 ----------

    public StoredResume saveResume(ResumeProfile profile, String filePath) {
        LocalDateTime now = UtcStamp.now();
        ResumeRow row = new ResumeRow();
        row.setLabel(resumeLabel(profile, filePath));
        row.setFilePath(Text.safe(filePath));
        row.setPayload(codec.write(profile));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        resumes.insert(row);
        return new StoredResume(row.getId(), row.getLabel(), row.getFilePath(), profile);
    }

    /** 列表上显示的名字。优先候选人姓名，退回文件名。 */
    private static String resumeLabel(ResumeProfile profile, String filePath) {
        if (Text.notBlank(profile.getCandidateName())) {
            return Text.trim(profile.getCandidateName(), 60);
        }
        if (Text.notBlank(profile.getSourceName())) {
            return Text.trim(profile.getSourceName(), 60);
        }
        String path = Text.safe(filePath);
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "未命名简历" : Text.trim(name, 60);
    }

    public StoredResume getResume(int resumeId) {
        ResumeRow row = resumes.selectById(resumeId);
        if (row == null) {
            return null;
        }
        ResumeProfile profile = codec.read(row.getPayload(), ResumeProfile.class);
        return profile == null ? null
                : new StoredResume(row.getId(), row.getLabel(), row.getFilePath(), profile);
    }

    public List<StoredResume> listResumes(int limit) {
        List<StoredResume> out = new ArrayList<>();
        for (ResumeRow row : resumes.listRecent(limit)) {
            ResumeProfile profile = codec.read(row.getPayload(), ResumeProfile.class);
            if (profile != null) {
                out.add(new StoredResume(row.getId(), row.getLabel(), row.getFilePath(), profile));
            }
        }
        return out;
    }

    // ---------- 岗位 ----------

    public StoredJob saveJob(JobDescription job) {
        LocalDateTime now = UtcStamp.now();
        JobRow row = new JobRow();
        row.setLabel(jobLabel(job));
        row.setCompany(Text.trim(job.getCompany(), 120));
        row.setTitle(Text.trim(job.getTitle(), 120));
        row.setPayload(codec.write(job));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        jobs.insert(row);
        return new StoredJob(row.getId(), row.getLabel(), job);
    }

    private static String jobLabel(JobDescription job) {
        String composed = (Text.safe(job.getCompany()) + " " + Text.safe(job.getTitle())).strip();
        if (!composed.isBlank()) {
            return Text.trim(composed, 120);
        }
        if (Text.notBlank(job.getSourceName())) {
            return Text.trim(job.getSourceName(), 120);
        }
        return "未命名岗位";
    }

    public StoredJob getJob(int jobId) {
        JobRow row = jobs.selectById(jobId);
        if (row == null) {
            return null;
        }
        JobDescription job = codec.read(row.getPayload(), JobDescription.class);
        return job == null ? null : new StoredJob(row.getId(), row.getLabel(), job);
    }

    public List<StoredJob> listJobs(int limit) {
        List<StoredJob> out = new ArrayList<>();
        for (JobRow row : jobs.listRecent(limit)) {
            JobDescription job = codec.read(row.getPayload(), JobDescription.class);
            if (job != null) {
                out.add(new StoredJob(row.getId(), row.getLabel(), job));
            }
        }
        return out;
    }

    // ---------- 差距诊断 ----------

    /** 同一对简历与岗位只留一份，先删后插放在同一事务里。 */
    @Transactional
    public StoredGap saveGap(int resumeId, int jobId, GapReport report) {
        LocalDateTime now = UtcStamp.now();
        gaps.deleteByPair(resumeId, jobId);
        GapReportRow row = new GapReportRow();
        row.setResumeId(resumeId);
        row.setJobId(jobId);
        row.setMatchScore(report.getMatchScore());
        row.setPayload(codec.write(report));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        gaps.insertRow(row);
        return new StoredGap(row.getId() == null ? 0 : row.getId(), resumeId, jobId, report);
    }

    public StoredGap findGap(int resumeId, int jobId) {
        GapReportRow row = gaps.findByPair(resumeId, jobId);
        if (row == null) {
            return null;
        }
        GapReport report = codec.read(row.getPayload(), GapReport.class);
        return report == null ? null
                : new StoredGap(row.getId(), row.getResumeId(), row.getJobId(), report);
    }
}
