package ark.dto;

import java.time.Instant;
import java.util.List;

import ark.entity.ScriptClip;
import lombok.Builder;

/** Một clip trong bảng kịch bản. missingRefs khác rỗng → clip đang chờ ảnh tham chiếu, chưa gửi được. */
@Builder
public record ScriptClipDto(Long id, String code, int seq, String sceneTitle, String timecode, int duration,
                            List<String> refCodes, List<String> missingRefs, String prompt, String promptVi,
                            String dialogue, String note, List<String> warnings, ScriptClip.Status status,
                            int attempt, String taskId, String error, GeneratedFileDto file,
                            Instant startedAt, Instant finishedAt,
                            /** Phân tích vì sao bị chặn (category, summary, issues, fixedPrompt) – null nếu chưa có. */
                            Object blockAnalysis, boolean analyzing) {}
