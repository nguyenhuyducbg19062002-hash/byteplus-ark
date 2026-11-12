package ark.dto;

import java.time.Instant;
import java.util.List;

import ark.entity.JobRecord;
import lombok.Builder;

@Builder
public record JobDto(String id, String type, String title, JobRecord.Status status, String taskId, String error,
                     Instant createdAt, Instant finishedAt, List<String> logs, List<GeneratedFileDto> files) {}
