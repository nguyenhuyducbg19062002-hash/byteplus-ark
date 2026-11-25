package ark.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ark.dto.PageResponse;
import ark.dto.ScriptClipDto;
import ark.dto.ScriptClipUpdateRequest;
import ark.dto.ScriptGenerateRefsRequest;
import ark.dto.ScriptReferencePromptRequest;
import ark.dto.ScriptImportResult;
import ark.dto.ScriptProjectDto;
import ark.dto.ScriptProjectUpdateRequest;
import ark.dto.ScriptRedoRequest;
import ark.dto.ScriptReferenceDto;
import ark.dto.ScriptReferenceUpdateRequest;
import ark.service.ScriptImportService;
import ark.service.ScriptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Kịch bản Seedance chạy hàng loạt: nhập Excel → gán ảnh tham chiếu → chạy tự động / làm lại clip. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ScriptProjectController {
    private final ScriptService service;
    private final ScriptImportService importer;

    @GetMapping("/script-projects")
    public List<ScriptProjectDto> list() {
        return service.list();
    }

    /**
     * Nhập file Excel thành dự án mới, hoặc nhập lại vào dự án có sẵn (projectId). Nhận một file ({@code file}) hoặc
     * nhiều file cùng lúc ({@code files}), vd bộ all-video-prompts.xlsx + reference-prompts.xlsx + voice-overlay.xlsx.
     */
    @PostMapping("/script-projects/import")
    public ResponseEntity<ScriptImportResult> importExcel(@RequestParam(value = "file", required = false) MultipartFile file,
                                                          @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                                          @RequestParam(required = false) Long projectId,
                                                          @RequestParam(required = false) String name) throws IOException {
        List<MultipartFile> all = new java.util.ArrayList<>();
        if (file != null) all.add(file);
        if (files != null) all.addAll(files);
        all.removeIf(f -> f == null || f.isEmpty());
        if (all.isEmpty()) throw new IllegalArgumentException("Chưa chọn file Excel");
        List<InputStream> opened = new java.util.ArrayList<>();
        try {
            List<ScriptImportService.Source> sources = new java.util.ArrayList<>();
            for (MultipartFile f : all) {
                String fn = f.getOriginalFilename() == null ? "kich-ban.xlsx" : f.getOriginalFilename();
                if (!fn.toLowerCase().endsWith(".xlsx")) throw new IllegalArgumentException("Chỉ nhận file Excel .xlsx: " + fn);
                InputStream in = f.getInputStream();
                opened.add(in);
                sources.add(new ScriptImportService.Source(in, fn.replaceFirst("^.*[/\\\\]", "")));
            }
            ScriptImportResult r = importer.importFiles(sources, projectId, name);
            return ResponseEntity.status(projectId == null ? HttpStatus.CREATED : HttpStatus.OK).body(r);
        } finally {
            for (InputStream in : opened) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // đã đọc xong
                }
            }
        }
    }

    @GetMapping("/script-projects/{id}")
    public ScriptProjectDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/script-projects/{id}")
    public ScriptProjectDto update(@PathVariable Long id, @Valid @RequestBody ScriptProjectUpdateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/script-projects/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/script-projects/{id}/start")
    public ScriptProjectDto start(@PathVariable Long id) {
        return service.start(id);
    }

    @PostMapping("/script-projects/{id}/pause")
    public ScriptProjectDto pause(@PathVariable Long id) {
        return service.pause(id);
    }

    @PostMapping("/script-projects/{id}/retry-failed")
    public ScriptProjectDto retryFailed(@PathVariable Long id) {
        return service.retryFailed(id);
    }

    /** Tiếp tục lượt chạy clip đã chọn đang tạm dừng. */
    @PostMapping("/script-projects/{id}/resume-selected")
    public ScriptProjectDto resumeSelected(@PathVariable Long id) {
        return service.resumeSelected(id);
    }

    /** Chạy chỉ các clip / cảnh đã chọn – không kéo theo cả kịch bản. */
    @PostMapping("/script-projects/{id}/run-clips")
    public ScriptProjectDto runClips(@PathVariable Long id, @RequestBody ark.dto.ScriptRunClipsRequest req) {
        return service.runClips(id, req);
    }

    /** Các cảnh của kịch bản (theo thứ tự) kèm số clip tổng / xong / lỗi. */
    @GetMapping("/script-projects/{id}/scenes")
    public List<java.util.Map<String, Object>> scenes(@PathVariable Long id) {
        return service.scenes(id);
    }

    @PostMapping("/script-projects/{id}/redo")
    public ScriptProjectDto redo(@PathVariable Long id, @Valid @RequestBody ScriptRedoRequest req) {
        return service.redo(id, req);
    }

    /** status: PENDING | GENERATING | SUCCEEDED | FAILED | CANCELLED | BLOCKED (chờ ảnh) | WARNING (có cảnh báo). */
    @GetMapping("/script-projects/{id}/clips")
    public PageResponse<ScriptClipDto> clips(@PathVariable Long id,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "1") @Min(1) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        service.project(id);
        return service.clips(id, status, q, page, size);
    }

    @GetMapping("/script-projects/{id}/references")
    public List<ScriptReferenceDto> references(@PathVariable Long id) {
        service.project(id);
        return service.references(id);
    }

    @PutMapping("/script-projects/{id}/references/{code}")
    public ScriptReferenceDto setReference(@PathVariable Long id, @PathVariable String code,
                                           @RequestBody ScriptReferenceUpdateRequest req) {
        return service.setReference(id, code, req.getFileId());
    }

    @PostMapping("/script-projects/{id}/references/{code}/upload")
    public ScriptReferenceDto uploadReference(@PathVariable Long id, @PathVariable String code,
                                              @RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return service.uploadReference(id, code, file.getOriginalFilename(), in);
        }
    }

    /** Ghép tự động mã còn thiếu với ảnh trong thư viện có tên file trùng mã. */
    @PostMapping("/script-projects/{id}/references/auto-match")
    public List<ScriptReferenceDto> autoMatch(@PathVariable Long id) {
        service.project(id);
        service.autoMatchReferences(id);
        return service.references(id);
    }

    /** Tải hàng loạt về máy (.zip): what = videos | images | all. */
    @GetMapping("/script-projects/{id}/download")
    public void download(@PathVariable Long id, @RequestParam(defaultValue = "all") String what,
                         jakarta.servlet.http.HttpServletResponse res) throws IOException {
        String name = service.project(id).getName();
        String label = switch (what.toLowerCase()) {
            case "videos" -> "video";
            case "images" -> "anh-tham-chieu";
            default -> "tat-ca";
        };
        ark.service.ZipExport.send(res, name + " - " + label + ".zip", service.exportItems(id, what));
    }

    /** Ghép asset Virtual Portrait (Active, đặt tên theo mã) vào các mã: tải về thư viện + gán → clip gửi asset://. */
    @PostMapping("/script-projects/{id}/references/match-vp-assets")
    public List<ScriptReferenceDto> matchVpAssets(@PathVariable Long id) throws IOException, InterruptedException {
        service.matchVpAssets(id);
        return service.references(id);
    }

    /** Nhập thư mục ảnh: gửi danh sách tên file → nhận gợi ý mã cho từng file. */
    @PostMapping("/script-projects/{id}/references/match-files")
    public List<ark.dto.ScriptFileMatchDto> matchFiles(@PathVariable Long id, @Valid @RequestBody ark.dto.ScriptFileMatchRequest req) {
        return service.matchFiles(id, req.getFiles());
    }

    /** Tạo ảnh tham chiếu bằng Seedream từ prompt ảnh của từng mã (chạy nền, song song). */
    @PostMapping("/script-projects/{id}/references/generate")
    public List<ScriptReferenceDto> generateReferences(@PathVariable Long id, @RequestBody(required = false) ScriptGenerateRefsRequest req) {
        return service.generateReferences(id, req == null ? null : req.getCodes(), req != null && req.isOverwrite());
    }

    /** Duyệt ảnh AI chờ duyệt: đổi tên thành MÃ.đuôi trong output/assets/&lt;dự án&gt;/ (tự tạo thư mục), gán cho mã. */
    @PostMapping("/script-projects/{id}/references/{code}/approve")
    public ScriptReferenceDto approveReference(@PathVariable Long id, @PathVariable String code) throws IOException {
        return service.approveReference(id, code);
    }

    /** Bỏ ảnh AI chờ duyệt (xoá file). */
    @PostMapping("/script-projects/{id}/references/{code}/reject")
    public ScriptReferenceDto rejectReference(@PathVariable Long id, @PathVariable String code) {
        return service.rejectReference(id, code);
    }

    @PostMapping("/script-projects/{id}/references/approve-all")
    public List<ScriptReferenceDto> approveAll(@PathVariable Long id) {
        return service.approveAll(id);
    }

    @PostMapping("/script-projects/{id}/references/stop-generate")
    public List<ScriptReferenceDto> stopGenerating(@PathVariable Long id) {
        service.project(id);
        return service.stopGenerating(id);
    }

    /** Model ngôn ngữ Dola-Seed viết prompt ảnh cho mã. save=false: chỉ trả về {prompt}; save=true: lưu luôn, trả kèm {reference}. */
    @PostMapping("/script-projects/{id}/references/{code}/ai-prompt")
    public java.util.Map<String, Object> aiPrompt(@PathVariable Long id, @PathVariable String code,
                                                  @RequestParam(defaultValue = "false") boolean save)
            throws IOException, InterruptedException {
        return service.aiPrompt(id, code, save);
    }

    @PutMapping("/script-projects/{id}/references/{code}/prompt")
    public ScriptReferenceDto updateReferencePrompt(@PathVariable Long id, @PathVariable String code,
                                                    @Valid @RequestBody ScriptReferencePromptRequest req) {
        return service.updateReferencePrompt(id, code, req.getPrompt(), req.isGenerate());
    }

    @PutMapping("/script-clips/{clipId}")
    public ScriptClipDto updateClip(@PathVariable Long clipId, @Valid @RequestBody ScriptClipUpdateRequest req) {
        return service.updateClip(clipId, req);
    }

    @PostMapping("/script-clips/{clipId}/run")
    public ScriptClipDto runClip(@PathVariable Long clipId) {
        return service.runClip(clipId);
    }

    /** Clip bị kiểm duyệt chặn: AI chỉ ra bằng tiếng Việt vi phạm gì, đoạn nào, kèm prompt đã sửa. */
    @PostMapping("/script-clips/{clipId}/analyze-block")
    public ScriptClipDto analyzeBlock(@PathVariable Long clipId) throws Exception {
        return service.analyzeBlock(clipId);
    }

    @PostMapping("/script-clips/{clipId}/cancel")
    public ScriptClipDto cancelClip(@PathVariable Long clipId) {
        return service.cancelClip(clipId);
    }
}
