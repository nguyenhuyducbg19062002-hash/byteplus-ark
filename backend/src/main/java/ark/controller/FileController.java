package ark.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ark.dto.GeneratedFileDto;
import ark.dto.PageResponse;
import ark.entity.GeneratedFile;
import ark.entity.MediaKind;
import ark.service.FileLibraryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Local library (output folder tracked in the DB). */
@Validated
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {
    private final FileLibraryService fileLibraryService;

    @GetMapping
    public PageResponse<GeneratedFileDto> list(@RequestParam(required = false) MediaKind kind,
                                               @RequestParam(defaultValue = "1") @Min(1) int page,
                                               @RequestParam(defaultValue = "24") @Min(1) @Max(200) int size,
                                               /** all (mặc định) / none (không thuộc dự án) / id dự án */
                                               @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = "all|none|[0-9]+") String project) {
        return fileLibraryService.list(kind, project, page, size);
    }

    /** Tải nhiều file về máy (.zip): theo danh sách ids, hoặc mọi file của một loại (kind) / cả thư viện. */
    @GetMapping("/download")
    public void download(@RequestParam(required = false) java.util.List<Long> ids,
                         @RequestParam(required = false) MediaKind kind,
                         @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = "all|none|[0-9]+") String project,
                         jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        java.util.List<GeneratedFile> list = ids != null && !ids.isEmpty()
                ? fileLibraryService.existing(ids) : fileLibraryService.allOfKind(kind);
        if ((ids == null || ids.isEmpty()) && project != null && !project.equals("all")) {
            Long pid = project.equals("none") ? null : Long.valueOf(project);
            list = list.stream().filter(f -> java.util.Objects.equals(f.getProjectId(), pid)).toList();
        }
        String sub = kind == null ? "" : kind == MediaKind.VIDEO ? "video/" : kind == MediaKind.IMAGE ? "anh/" : "";
        java.util.List<ark.service.ZipExport.Item> items = list.stream()
                .map(f -> new ark.service.ZipExport.Item((sub.isEmpty() ? folderOf(f) : sub) + f.getName(), fileLibraryService.pathOf(f)))
                .toList();
        String label = ids != null && !ids.isEmpty() ? "da-chon" : kind == null ? "tat-ca" : kind == MediaKind.VIDEO ? "video" : "anh";
        ark.service.ZipExport.send(res, "thu-vien-" + label + ".zip", items);
    }

    private static String folderOf(GeneratedFile f) {
        return f.getKind() == MediaKind.VIDEO ? "video/" : f.getKind() == MediaKind.IMAGE ? "anh/" : "khac/";
    }

    @GetMapping("/{id}")
    public GeneratedFileDto get(@PathVariable Long id) {
        return fileLibraryService.toDto(fileLibraryService.get(id));
    }

    /** File content (Range supported so videos can seek). */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id) {
        GeneratedFile f = fileLibraryService.get(id);
        Path p = fileLibraryService.pathOf(f);
        if (!Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        MediaType type = MediaTypeFactory.getMediaType(f.getName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(p));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) throws Exception {
        fileLibraryService.delete(id);
    }
}
