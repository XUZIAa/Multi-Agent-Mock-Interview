package com.interviewer.data.dto;

import com.interviewer.domain.review.MistakeItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 错题本条目。hitCount 是同一知识点累计答错的次数。 */
public record StoredMistake(int id, @Schema(nullable = true) Integer sessionId, MistakeItem item,
                            int hitCount, boolean mastered, LocalDateTime lastSeenAt) {
}
