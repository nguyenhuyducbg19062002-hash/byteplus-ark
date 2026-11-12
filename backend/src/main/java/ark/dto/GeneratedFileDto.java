package ark.dto;

import java.time.Instant;

import ark.entity.GeneratedFile;
import ark.entity.MediaKind;
import lombok.Builder;

/** A file in the local library, as returned to the frontend. url = /api/v1/files/{id}/content */
@Builder
public record GeneratedFileDto(Long id, String name, String path, MediaKind kind, String url, Long size,
                               Instant createdAt, String model, String prompt, String taskId,
                               String assetId, String assetStatus, String assetError, boolean sourceUrlValid) {
    public static GeneratedFileDto from(GeneratedFile f, Long size) {
        return GeneratedFileDto.builder()
                .id(f.getId())
                .name(f.getName())
                .path(f.getPath())
                .kind(f.getKind())
                .url("/api/v1/files/" + f.getId() + "/content")
                .size(size)
                .createdAt(f.getCreatedAt())
                .model(f.getModel())
                .prompt(f.getPrompt())
                .taskId(f.getTaskId())
                .assetId(f.getAssetId())
                .assetStatus(f.getAssetStatus())
                .assetError(f.getAssetError())
                .sourceUrlValid(f.isSourceUrlValid())
                .build();
    }
}
