package ark.dto;

import java.time.Instant;
import java.util.Map;

import ark.entity.ScriptProject;
import lombok.Builder;

/** Dự án kịch bản + thống kê clip theo trạng thái (để vẽ ô số liệu và bộ lọc). */
@Builder
public record ScriptProjectDto(Long id, String name, String sourceFile, String folder, String model, String resolution,
                               String ratio, boolean generateAudio, int maxParallel, int maxAttempts,
                               String imageModel, String imageSize, String promptModel, int imagesPending, int imagesFailed,
                               int imagesReview, boolean autoApproveImages, String refSaveMode, String refSaveDir,
                               ScriptProject.Status status, String pausedReason, Instant cooldownUntil, Instant createdAt,
                               Map<String, Long> counts, int refsTotal, int refsMissing, int blockedClips, int warningClips) {}
