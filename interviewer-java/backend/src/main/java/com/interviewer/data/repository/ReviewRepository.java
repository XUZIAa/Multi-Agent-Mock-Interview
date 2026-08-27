package com.interviewer.data.repository;

import com.interviewer.core.Text;
import com.interviewer.core.type.GapSeverity;
import com.interviewer.core.type.ScoreDimension;
import com.interviewer.data.UtcStamp;
import com.interviewer.data.dto.StoredMistake;
import com.interviewer.data.dto.TrendPoint;
import com.interviewer.data.entity.MistakeRow;
import com.interviewer.data.entity.ReviewRow;
import com.interviewer.data.entity.SkillTrendRow;
import com.interviewer.data.mapper.MistakeMapper;
import com.interviewer.data.mapper.ReviewMapper;
import com.interviewer.data.mapper.SkillTrendMapper;
import com.interviewer.domain.review.DimensionScore;
import com.interviewer.domain.review.MistakeItem;
import com.interviewer.domain.review.ReviewReport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 复盘、错题本与成长曲线的仓储。 */
@Repository
public class ReviewRepository {

    private final ReviewMapper reviews;
    private final MistakeMapper mistakes;
    private final SkillTrendMapper trends;
    private final SessionRepository sessionRepo;
    private final JsonCodec codec;

    public ReviewRepository(ReviewMapper reviews, MistakeMapper mistakes, SkillTrendMapper trends,
                            SessionRepository sessionRepo, JsonCodec codec) {
        this.reviews = reviews;
        this.mistakes = mistakes;
        this.trends = trends;
        this.sessionRepo = sessionRepo;
        this.codec = codec;
    }

    /**
     * 报告本体、错题、趋势点分三步写。
     *
     * <p>刻意不包在一个事务里：任一步失败不该把已落地的部分回滚掉。报告本体最重要，
     * 先写它；错题合并逐条独立，一条冲突不影响其余。
     */
    public void saveReview(ReviewReport report) {
        saveReportBody(report);
        mergeMistakes(report);
        saveTrend(report);
    }

    @Transactional
    public void saveReportBody(ReviewReport report) {
        LocalDateTime now = UtcStamp.now();
        ReviewRow row = new ReviewRow();
        row.setSessionId(report.getSessionId());
        row.setOverallScore(report.getOverallScore());
        row.setPayload(codec.write(report));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        reviews.upsert(row);
        sessionRepo.setScore(report.getSessionId(), report.getOverallScore());
    }

    /**
     * 错题按知识点合并。
     *
     * <p>已掌握的不参与合并：那表示用户认为自己补上了，再答错该重开一条。
     * 严重度只升不降——同一个知识点在更关键的岗位上答错，说明它比之前判断的更要紧。
     */
    private void mergeMistakes(ReviewReport report) {
        for (MistakeItem item : report.getMistakes()) {
            if (!Text.notBlank(item.knowledgePoint())) {
                continue;
            }
            MistakeRow existing = mistakes.findPendingByPoint(item.knowledgePoint());
            LocalDateTime now = UtcStamp.now();
            if (existing == null) {
                MistakeRow row = new MistakeRow();
                row.setSessionId(report.getSessionId());
                row.setKnowledgePoint(item.knowledgePoint());
                row.setTopic(item.topic());
                row.setQuestion(item.question());
                row.setCandidateAnswer(item.candidateAnswer());
                row.setKeyPoints(codec.write(item.keyPoints()));
                row.setSeverity(item.severity().value());
                row.setReviewHint(item.reviewHint());
                row.setHitCount(1);
                row.setMastered(false);
                row.setLastSeenAt(now);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                mistakes.insert(row);
                continue;
            }
            existing.setHitCount(existing.getHitCount() + 1);
            existing.setLastSeenAt(now);
            existing.setUpdatedAt(now);
            existing.setSessionId(report.getSessionId());
            existing.setQuestion(preferNew(item.question(), existing.getQuestion()));
            existing.setCandidateAnswer(
                    preferNew(item.candidateAnswer(), existing.getCandidateAnswer()));
            if (!item.keyPoints().isEmpty()) {
                existing.setKeyPoints(codec.write(item.keyPoints()));
            }
            existing.setReviewHint(preferNew(item.reviewHint(), existing.getReviewHint()));
            if (item.severity() == GapSeverity.BLOCKER) {
                existing.setSeverity(item.severity().value());
            }
            mistakes.updateById(existing);
        }
    }

    private static String preferNew(String fresh, String old) {
        return Text.notBlank(fresh) ? fresh : Text.safe(old);
    }

    @Transactional
    public void saveTrend(ReviewReport report) {
        LocalDateTime now = UtcStamp.now();
        for (DimensionScore dim : report.getDimensions()) {
            if (dim.dimension() == null) {
                continue;
            }
            SkillTrendRow row = new SkillTrendRow();
            row.setSessionId(report.getSessionId());
            row.setDimension(dim.dimension().value());
            row.setScore(dim.score());
            row.setRecordedAt(now);
            trends.upsert(row);
        }
    }

    public ReviewReport getReview(int sessionId) {
        ReviewRow row = reviews.findBySession(sessionId);
        return row == null ? null : codec.read(row.getPayload(), ReviewReport.class);
    }

    // ---------- 错题本 ----------

    public List<StoredMistake> listMistakes(boolean includeMastered, String topic, int limit) {
        List<StoredMistake> out = new ArrayList<>();
        for (MistakeRow row : mistakes.listFiltered(includeMastered, Text.safe(topic), limit)) {
            MistakeItem item = new MistakeItem(
                    row.getKnowledgePoint(), row.getTopic(), row.getQuestion(),
                    row.getCandidateAnswer(), codec.readStringList(row.getKeyPoints()),
                    GapSeverity.of(row.getSeverity()), row.getReviewHint());
            out.add(new StoredMistake(row.getId(), row.getSessionId(), item,
                    row.getHitCount() == null ? 0 : row.getHitCount(),
                    Boolean.TRUE.equals(row.getMastered()), row.getLastSeenAt()));
        }
        return out;
    }

    public List<String> topics() {
        return mistakes.topics();
    }

    public void setMastered(int mistakeId, boolean mastered) {
        mistakes.setMastered(mistakeId, mastered, UtcStamp.now());
    }

    public void deleteMistake(int mistakeId) {
        mistakes.deleteById(mistakeId);
    }

    /** 返回 (待复习, 已掌握) 两个计数。 */
    public int[] mistakeCounts() {
        return new int[]{mistakes.countByMastered(false), mistakes.countByMastered(true)};
    }

    // ---------- 成长曲线 ----------

    /**
     * 各维度的历史序列。
     *
     * <p>一次跨维度取 limit×维度数 行再分组，而不是每个维度各查一次。代价是某场缺维度时
     * 会多取到几场，收益是六次查询变一次——与 Python 版同样的取舍。
     */
    public Map<ScoreDimension, List<TrendPoint>> dimensionSeries(int limit) {
        List<SkillTrendRow> rows = trends.recentAll(limit * ScoreDimension.values().length);
        Map<ScoreDimension, List<TrendPoint>> series = new LinkedHashMap<>();
        // 查出来是倒序，翻正后才是时间轴
        Collections.reverse(rows);
        for (SkillTrendRow row : rows) {
            ScoreDimension dim = ScoreDimension.of(row.getDimension());
            if (dim == null) {
                continue;
            }
            series.computeIfAbsent(dim, k -> new ArrayList<>()).add(new TrendPoint(
                    row.getSessionId(), row.getRecordedAt(),
                    row.getScore() == null ? 0.0 : row.getScore()));
        }
        return series;
    }

    public List<TrendPoint> overallSeries(int limit) {
        List<ReviewRow> rows = new ArrayList<>(reviews.recentSeries(limit));
        Collections.reverse(rows);
        List<TrendPoint> out = new ArrayList<>(rows.size());
        for (ReviewRow row : rows) {
            out.add(new TrendPoint(row.getSessionId(), row.getCreatedAt(),
                    row.getOverallScore() == null ? 0.0 : row.getOverallScore()));
        }
        return out;
    }
}
