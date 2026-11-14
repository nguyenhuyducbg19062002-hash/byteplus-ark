package ark.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ark.config.Config;
import ark.dto.GeneratedFileDto;
import ark.dto.PageResponse;
import ark.dto.ScriptClipDto;
import ark.dto.ScriptClipUpdateRequest;
import ark.dto.ScriptProjectDto;
import ark.dto.ScriptProjectUpdateRequest;
import ark.dto.ScriptRedoRequest;
import ark.dto.ScriptReferenceDto;
import ark.entity.GeneratedFile;
import ark.entity.MediaKind;
import ark.entity.ScriptClip;
import ark.entity.ScriptProject;
import ark.entity.ScriptReference;
import ark.integration.byteplus.Models;
import ark.integration.byteplus.SavedOutput;
import ark.repository.GeneratedFileRepository;
import ark.repository.ScriptClipRepository;
import ark.repository.ScriptProjectRepository;
import ark.repository.ScriptReferenceRepository;
import lombok.RequiredArgsConstructor;

/** Nghiệp vụ dự án kịch bản: cấu hình, ảnh tham chiếu, chạy / tạm dừng, làm lại clip. Việc gửi clip do {@link ScriptRunner} làm. */
@Service
@RequiredArgsConstructor
public class ScriptService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScriptService.class);
    private static final Pattern RANGE = Pattern.compile("^(\\d+)\\s*[-–]\\s*(\\d+)$");
    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp", "bmp");

    private final Config config;
    private final ScriptProjectRepository projects;
    private final ScriptClipRepository clips;
    private final ScriptReferenceRepository references;
    private final GeneratedFileRepository files;
    private final FileLibraryService fileLibrary;
    private final ScriptRunner runner;
    private final ScriptImageRunner imageRunner;
    private final AssetLibraryService assetLibrary;
    private final ScriptPromptWriter promptWriter;
    private final ScriptBlockAnalyzer blockAnalyzer;

    // ---------- dự án ----------

    public List<ScriptProjectDto> list() {
        return projects.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    public ScriptProjectDto get(Long id) {
        return toDto(project(id));
    }

    public ScriptProject project(Long id) {
        return projects.findById(id).orElseThrow(() -> new NoSuchElementException("Không có dự án kịch bản " + id));
    }

    @Transactional
    public ScriptProjectDto update(Long id, ScriptProjectUpdateRequest req) {
        ScriptProject p = project(id);
        if (req.getName() != null) p.setName(req.getName().trim());
        Models.VideoModel m = Models.video(req.getModel() != null ? req.getModel() : p.getModel());
        if (!m.omni()) throw new IllegalArgumentException(m.name() + " không hỗ trợ ảnh tham chiếu – chọn Seedance 2.x");
        p.setModel(m.id());
        String res = req.getResolution() != null ? req.getResolution() : p.getResolution();
        if (!m.resolutions().contains(res)) {
            if (req.getResolution() != null) throw new IllegalArgumentException(m.name() + " không hỗ trợ độ phân giải " + res);
            res = m.defaultResolution();
        }
        p.setResolution(res);
        if (req.getRatio() != null) p.setRatio(req.getRatio());
        if (req.getGenerateAudio() != null) p.setGenerateAudio(req.getGenerateAudio());
        if (req.getMaxParallel() != null) p.setMaxParallel(req.getMaxParallel());
        if (req.getMaxAttempts() != null) p.setMaxAttempts(req.getMaxAttempts());
        if (req.getAutoApproveImages() != null) p.setAutoApproveImages(req.getAutoApproveImages());
        if (req.getRefSaveDir() != null) {
            String d = req.getRefSaveDir().trim();
            if (!d.isEmpty() && !Path.of(d).isAbsolute()) throw new IllegalArgumentException("Thư mục lưu ảnh phải là đường dẫn đầy đủ, vd D:\\Anh REF");
            p.setRefSaveDir(d.isEmpty() ? null : d);
        }
        if (req.getRefSaveMode() != null) p.setRefSaveMode(req.getRefSaveMode());
        if ("FOLDER".equals(p.getRefSaveMode()) && p.getRefSaveDir() == null) {
            throw new IllegalArgumentException("Chọn “Thư mục riêng” thì phải nhập đường dẫn thư mục");
        }
        if (req.getImageModel() != null) p.setImageModel(Models.image(req.getImageModel()).id());
        if (req.getPromptModel() != null) p.setPromptModel(Models.text(req.getPromptModel()).id());
        Models.ImageModel im = ScriptImageRunner.model(p);
        String size = req.getImageSize() != null ? req.getImageSize() : p.getImageSize();
        if (size == null || !im.sizes().contains(size)) {
            if (req.getImageSize() != null) throw new IllegalArgumentException(im.name() + " không hỗ trợ kích thước " + size);
            size = im.defaultSize();
        }
        p.setImageSize(size);
        return toDto(projects.save(p));
    }

    @Transactional
    public void delete(Long id) {
        ScriptProject p = project(id);
        if (p.getStatus() == ScriptProject.Status.RUNNING || clips.countByProjectIdAndStatus(id, ScriptClip.Status.GENERATING) > 0) {
            throw new IllegalStateException("Dự án đang chạy – tạm dừng và chờ các clip đang tạo xong rồi mới xoá");
        }
        clips.deleteByProjectId(id);
        references.deleteByProjectId(id);
        projects.delete(p);
    }

    /** Tiếp tục lượt “chỉ chạy clip đã chọn” đang tạm dừng (không kéo theo cả kịch bản). */
    @Transactional
    public ScriptProjectDto resumeSelected(Long id) {
        if (config.apiKey().isEmpty()) throw new IllegalStateException("Chưa có API key – nhập trong trang Cài đặt trước khi chạy");
        ScriptProject p = project(id);
        if (!p.isOnlySelected()) throw new IllegalStateException("Dự án không có lượt chạy clip đã chọn nào đang dở");
        p.setStatus(ScriptProject.Status.RUNNING);
        p.setPausedReason(null);
        p.setCooldownUntil(null);
        return toDto(projects.save(p));
    }

    /** Chạy cả kịch bản (nút “Chạy video”): mọi clip đang chờ, bỏ chế độ chỉ chạy clip đã chọn. */
    @Transactional
    public ScriptProjectDto start(Long id) {
        if (config.apiKey().isEmpty()) throw new IllegalStateException("Chưa có API key – nhập trong trang Cài đặt trước khi chạy");
        project(id);
        clips.clearRunSelected(id);
        ScriptProject p = project(id);
        p.setOnlySelected(false);
        p.setStatus(ScriptProject.Status.RUNNING);
        p.setPausedReason(null);
        p.setCooldownUntil(null);
        return toDto(projects.save(p));
    }

    @Transactional
    public ScriptProjectDto pause(Long id) {
        ScriptProject p = project(id);
        p.setStatus(ScriptProject.Status.PAUSED);
        long gen = clips.countByProjectIdAndStatus(id, ScriptClip.Status.GENERATING);
        p.setPausedReason("Đã tạm dừng" + (gen > 0 ? " – " + gen + " clip đang tạo vẫn chạy tiếp cho xong" : ""));
        return toDto(projects.save(p));
    }

    /** Chạy lại chỉ các clip lỗi / đã huỷ (không kéo theo clip chờ khác). */
    @Transactional
    public ScriptProjectDto retryFailed(Long id) {
        List<Long> ids = clips.findByProjectIdOrderBySeq(id).stream()
                .filter(c -> c.getStatus() == ScriptClip.Status.FAILED || c.getStatus() == ScriptClip.Status.CANCELLED)
                .map(ScriptClip::getId).toList();
        if (ids.isEmpty()) throw new IllegalStateException("Không có clip lỗi / đã huỷ");
        return startSelected(id, ids, c -> resetForRun(c, 0));
    }

    /**
     * Chạy chỉ các clip được chọn (theo id và / hoặc theo cảnh). Clip đang tạo bỏ qua; clip đã xong chỉ tạo lại khi redoDone;
     * clip đang chờ giữ nguyên (kể cả task đang theo dõi), clip lỗi / đã huỷ được đặt lại.
     */
    @Transactional
    public ScriptProjectDto runClips(Long id, ark.dto.ScriptRunClipsRequest req) {
        project(id);
        Set<Long> wantIds = req.getClipIds() == null ? Set.of() : Set.copyOf(req.getClipIds());
        Set<String> wantScenes = req.getScenes() == null ? Set.of() : Set.copyOf(req.getScenes());
        if (wantIds.isEmpty() && wantScenes.isEmpty()) throw new IllegalArgumentException("Chưa chọn clip hoặc cảnh nào");
        List<ScriptClip> hit = clips.findByProjectIdOrderBySeq(id).stream()
                .filter(c -> wantIds.contains(c.getId()) || wantScenes.contains(sceneOf(c))).toList();
        List<Long> ids = hit.stream().filter(c -> c.getStatus() != ScriptClip.Status.GENERATING)
                .filter(c -> req.isRedoDone() || c.getStatus() != ScriptClip.Status.SUCCEEDED)
                .map(ScriptClip::getId).toList();
        if (ids.isEmpty()) {
            throw new IllegalStateException("Các clip đã chọn đều đã xong hoặc đang tạo – tick “Tạo lại cả clip đã xong” nếu muốn tạo lại");
        }
        int prio = clips.maxPriority(id) + 1;
        return startSelected(id, ids, c -> {
            if (c.getStatus() == ScriptClip.Status.PENDING) c.setRunPriority(prio);
            else resetForRun(c, prio);
        });
    }

    /** Danh sách cảnh (theo cột cảnh trong Excel, giữ thứ tự kịch bản) kèm số clip theo trạng thái – để chọn chạy theo cảnh. */
    public List<Map<String, Object>> scenes(Long id) {
        project(id);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (ScriptClip c : clips.findByProjectIdOrderBySeq(id)) {
            Map<String, Object> s = out.computeIfAbsent(sceneOf(c), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("scene", k);
                m.put("total", 0);
                m.put("done", 0);
                m.put("failed", 0);
                m.put("firstCode", c.getCode());
                return m;
            });
            s.merge("total", 1, (a, b) -> (Integer) a + (Integer) b);
            if (c.getStatus() == ScriptClip.Status.SUCCEEDED) s.merge("done", 1, (a, b) -> (Integer) a + (Integer) b);
            if (c.getStatus() == ScriptClip.Status.FAILED) s.merge("failed", 1, (a, b) -> (Integer) a + (Integer) b);
        }
        return new ArrayList<>(out.values());
    }

    private static String sceneOf(ScriptClip c) {
        return c.getSceneTitle() == null || c.getSceneTitle().isBlank() ? "(Không có tên cảnh)" : c.getSceneTitle().trim();
    }

    /**
     * Đưa các clip vào lượt chạy mà KHÔNG kéo theo cả kịch bản: dự án đang chạy cả kịch bản thì chỉ cần đưa clip vào hàng đợi;
     * nếu không, dự án chuyển sang “chỉ chạy clip đã chọn” (đang chạy chế độ này thì thêm clip vào lượt hiện tại).
     */
    private ScriptProjectDto startSelected(Long id, List<Long> clipIds, java.util.function.Consumer<ScriptClip> prepare) {
        if (config.apiKey().isEmpty()) throw new IllegalStateException("Chưa có API key – nhập trong trang Cài đặt trước khi chạy");
        ScriptProject before = project(id);
        boolean running = before.getStatus() == ScriptProject.Status.RUNNING;
        boolean runningAll = running && !before.isOnlySelected();
        // Lượt chọn mới (đang tạm dừng giữa một lượt chọn thì thêm vào lượt đó, không bỏ lựa chọn cũ)
        if (!running && !before.isOnlySelected()) clips.clearRunSelected(id); // – chạy trước khi sửa clip (lệnh này xoá bộ nhớ đệm JPA)
        for (ScriptClip c : clips.findAllById(clipIds)) {
            prepare.accept(c);
            if (!runningAll) c.setRunSelected(true);
            clips.save(c);
        }
        ScriptProject p = project(id);
        if (!runningAll) {
            p.setOnlySelected(true);
            p.setStatus(ScriptProject.Status.RUNNING);
            p.setPausedReason(null);
            p.setCooldownUntil(null);
            p = projects.save(p);
        }
        return toDto(p);
    }

    /** Làm lại clip theo mã ("005, 012-015, 019A"), có thể kèm ghi chú đạo diễn ghép vào cuối prompt. */
    @Transactional
    public ScriptProjectDto redo(Long id, ScriptRedoRequest req) {
        List<ScriptClip> all = clips.findByProjectIdOrderBySeq(id);
        Set<ScriptClip> picked = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String tok : req.getCodes().replaceAll("\\s*[-–]\\s*", "-").split("[,;\\s]+")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            Matcher m = RANGE.matcher(t);
            if (m.matches()) {
                int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
                List<ScriptClip> hit = all.stream().filter(c -> {
                    Integer n = numberOf(c.getCode());
                    return n != null && n >= Math.min(a, b) && n <= Math.max(a, b);
                }).toList();
                if (hit.isEmpty()) unknown.add(t);
                picked.addAll(hit);
            } else {
                List<ScriptClip> hit = all.stream().filter(c -> c.getCode().equalsIgnoreCase(t)
                        || (t.matches("\\d+") && Integer.valueOf(Integer.parseInt(t)).equals(numberOfExact(c.getCode())))).toList();
                if (hit.isEmpty()) unknown.add(t);
                picked.addAll(hit);
            }
        }
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Không có clip: " + String.join(", ", unknown));
        if (picked.isEmpty()) throw new IllegalArgumentException("Chưa nhập mã clip");
        List<String> busy = picked.stream().filter(c -> c.getStatus() == ScriptClip.Status.GENERATING).map(ScriptClip::getCode).toList();
        if (!busy.isEmpty()) throw new IllegalStateException("Clip đang tạo, dừng trước khi làm lại: " + String.join(", ", busy));
        int prio = clips.maxPriority(id) + 1;
        String note = req.getNote() == null ? "" : req.getNote().trim();
        // Chỉ chạy các clip làm lại, không kéo theo cả kịch bản
        return startSelected(id, picked.stream().map(ScriptClip::getId).toList(), c -> {
            if (!note.isEmpty()) {
                c.setPrompt(c.getPrompt() + "\n\nREVISION NOTE FROM THE DIRECTOR (apply this change, keep everything else unchanged): " + note);
            }
            resetForRun(c, prio);
        });
    }

    // ---------- clip ----------

    public PageResponse<ScriptClipDto> clips(Long id, String status, String q, int page, int size) {
        Map<String, ScriptReference> refs = refMap(id);
        ScriptRunner.RefCheck check = runner.refCheck(refs);
        List<ScriptClip> all = clips.findByProjectIdOrderBySeq(id);
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<ScriptClip> filtered = all.stream().filter(c -> {
            if (status != null && !status.isBlank()) {
                switch (status.toUpperCase(Locale.ROOT)) {
                    case "BLOCKED" -> {
                        if (c.getStatus() != ScriptClip.Status.PENDING || check.missing(c).isEmpty()) return false;
                    }
                    case "WARNING" -> {
                        if (c.getWarnings() == null) return false;
                    }
                    default -> {
                        if (!c.getStatus().name().equalsIgnoreCase(status)) return false;
                    }
                }
            }
            if (query.isEmpty()) return true;
            return c.getCode().toLowerCase(Locale.ROOT).contains(query)
                    || (c.getSceneTitle() != null && c.getSceneTitle().toLowerCase(Locale.ROOT).contains(query))
                    || (c.getRefCodes() != null && c.getRefCodes().toLowerCase(Locale.ROOT).contains(query))
                    || c.getPrompt().toLowerCase(Locale.ROOT).contains(query);
        }).toList();
        int from = Math.min((Math.max(page, 1) - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<ScriptClipDto> items = filtered.subList(from, to).stream().map(c -> toDto(c, check)).toList();
        return new PageResponse<>(items, filtered.size(), page, size);
    }

    public ScriptClip clip(Long clipId) {
        return clips.findById(clipId).orElseThrow(() -> new NoSuchElementException("Không có clip " + clipId));
    }

    @Transactional
    public ScriptClipDto updateClip(Long clipId, ScriptClipUpdateRequest req) {
        ScriptClip c = clip(clipId);
        if (c.getStatus() == ScriptClip.Status.GENERATING) throw new IllegalStateException("Clip đang tạo – dừng trước khi sửa");
        c.setPrompt(req.getPrompt().trim());
        if (req.getDuration() != null) c.setDuration(req.getDuration());
        clips.saveAndFlush(c);
        if (req.isRun()) { // chỉ chạy clip này
            int prio = clips.maxPriority(c.getProjectId()) + 1;
            startSelected(c.getProjectId(), List.of(clipId), x -> resetForRun(x, prio));
        }
        return toDto(clip(clipId), runner.refCheck(refMap(c.getProjectId())));
    }

    @Transactional
    public ScriptClipDto runClip(Long clipId) {
        ScriptClip c = clip(clipId);
        if (c.getStatus() == ScriptClip.Status.GENERATING) throw new IllegalStateException("Clip đang tạo");
        int prio = clips.maxPriority(c.getProjectId()) + 1;
        // Chỉ chạy clip này (clip đang chờ giữ task đang theo dõi, clip khác thì tạo lại)
        startSelected(c.getProjectId(), List.of(clipId), x -> {
            if (x.getStatus() == ScriptClip.Status.PENDING) x.setRunPriority(prio);
            else resetForRun(x, prio);
        });
        return toDto(clip(clipId), runner.refCheck(refMap(c.getProjectId())));
    }

    @Transactional
    public ScriptClipDto cancelClip(Long clipId) {
        ScriptClip c = clip(clipId);
        if (!runner.requestCancel(clipId) && clips.cancelPending(clipId, "Đã huỷ", Instant.now()) == 0) {
            // Không đang chạy cũng không còn PENDING: có thể vừa được bộ chạy nhận → yêu cầu dừng lần nữa
            runner.requestCancel(clipId);
        }
        return toDto(clip(clipId), runner.refCheck(refMap(c.getProjectId())));
    }

    private static void resetForRun(ScriptClip c, int priority) {
        c.setStatus(ScriptClip.Status.PENDING);
        c.setAttempt(0);
        c.setTaskId(null);
        c.setError(null);
        c.setFinishedAt(null);
        c.setRunPriority(priority);
        c.setBlockAnalysis(null);
    }

    /** Model ngôn ngữ phân tích vì sao clip bị kiểm duyệt chặn (tiếng Việt: loại vi phạm, đoạn nghi vấn, prompt đã sửa). */
    public ScriptClipDto analyzeBlock(Long clipId) throws Exception {
        ScriptClip c = clip(clipId);
        if (c.getStatus() != ScriptClip.Status.FAILED && c.getError() == null) {
            throw new IllegalStateException("Clip " + c.getCode() + " không bị lỗi – không có gì để phân tích");
        }
        blockAnalyzer.analyzeNow(clipId);
        return toDto(clip(clipId), runner.refCheck(refMap(c.getProjectId())));
    }

    // ---------- ảnh tham chiếu ----------

    /**
     * File để tải hàng loạt của dự án: what = videos (clip đã xong, theo thứ tự kịch bản), images (ảnh tham chiếu đã gán)
     * hoặc all. Đường dẫn trong zip: video/&lt;tên file&gt;, anh-tham-chieu/&lt;loại&gt;/&lt;mã&gt;.&lt;đuôi&gt;.
     */
    public List<ZipExport.Item> exportItems(Long id, String what) {
        project(id);
        String w = what == null ? "all" : what.toLowerCase(Locale.ROOT);
        List<ZipExport.Item> out = new ArrayList<>();
        if (w.equals("all") || w.equals("videos")) {
            for (ScriptClip c : clips.findByProjectIdOrderBySeq(id)) {
                if (c.getStatus() != ScriptClip.Status.SUCCEEDED || c.getFileId() == null) continue;
                files.findById(c.getFileId()).ifPresent(f -> out.add(new ZipExport.Item("video/" + f.getName(), fileLibrary.pathOf(f))));
            }
        }
        if (w.equals("all") || w.equals("images")) {
            for (ScriptReference r : references.findByProjectIdOrderByKindAscCodeAsc(id)) {
                if (r.getFileId() == null) continue;
                files.findById(r.getFileId()).ifPresent(f -> {
                    String ext = f.getName().contains(".") ? f.getName().substring(f.getName().lastIndexOf('.')) : "";
                    String folder = switch (r.getKind()) {
                        case CHARACTER -> "nhan-vat";
                        case LOCATION -> "boi-canh";
                        case PROP -> "dao-cu";
                        default -> "khac";
                    };
                    out.add(new ZipExport.Item("anh-tham-chieu/" + folder + "/" + r.getCode() + ext, fileLibrary.pathOf(f)));
                });
            }
        }
        return out;
    }

    public List<ScriptReferenceDto> references(Long id) {
        Map<String, Integer> used = new LinkedHashMap<>();
        for (ScriptClip c : clips.findByProjectIdOrderBySeq(id)) for (String r : c.refList()) used.merge(r, 1, Integer::sum);
        List<ScriptReference> list = references.findByProjectIdOrderByKindAscCodeAsc(id);
        Map<String, ScriptReference> byCode = list.stream().collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
        ScriptRunner.RefCheck check = runner.refCheck(byCode);
        // Một truy vấn cho mọi file (ảnh đã gán + ảnh chờ duyệt) thay vì hai truy vấn mỗi mã
        List<Long> ids = list.stream().flatMap(r -> java.util.stream.Stream.of(r.getFileId(), r.getPendingFileId()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, GeneratedFileDto> dtos = ids.isEmpty() ? Map.of() : files.findByIdIn(ids).stream()
                .collect(Collectors.toMap(GeneratedFile::getId, fileLibrary::toDto, (a, b) -> a));
        return list.stream()
                .map(r -> new ScriptReferenceDto(r.getId(), r.getCode(), r.getKind(), r.getName(), r.getDescription(),
                        r.getFileId() == null ? null : dtos.get(r.getFileId()), used.getOrDefault(r.getCode(), 0),
                        r.getImagePrompt(), r.isPromptEdited(), r.getIdentityCode(), r.getImageStatus(),
                        r.getImageStatus() == ScriptReference.ImageStatus.GENERATING && imageRunner.lastLog(r.getId()) != null
                                ? imageRunner.lastLog(r.getId()) : r.getImageError(),
                        check.blocked(r) ? r.getBlockedReason() : null,
                        r.getPendingFileId() == null ? null : dtos.get(r.getPendingFileId())))
                .toList();
    }

    /**
     * Đưa mã vào hàng đợi tạo ảnh Seedream. codes rỗng = mọi mã đang được clip dùng mà chưa có ảnh dùng được.
     * overwrite = false thì bỏ qua mã đã có ảnh.
     */
    @Transactional
    public List<ScriptReferenceDto> generateReferences(Long id, List<String> codes, boolean overwrite) {
        if (config.apiKey().isEmpty()) throw new IllegalStateException("Chưa có API key – nhập trong trang Cài đặt trước");
        project(id);
        Set<String> used = clips.findByProjectIdOrderBySeq(id).stream().flatMap(c -> c.refList().stream()).collect(Collectors.toSet());
        Set<String> wanted = codes == null || codes.isEmpty() ? null : Set.copyOf(codes);
        int n = 0;
        for (ScriptReference r : references.findByProjectIdOrderByKindAscCodeAsc(id)) {
            if (wanted != null ? !wanted.contains(r.getCode()) : !used.contains(r.getCode())) continue;
            if (r.getImageStatus() == ScriptReference.ImageStatus.GENERATING || r.getImageStatus() == ScriptReference.ImageStatus.PENDING) continue;
            boolean reviewing = r.getImageStatus() == ScriptReference.ImageStatus.REVIEW;
            boolean has = reviewing || (r.getFileId() != null && runner.fileUsable(r.getFileId()));
            if (has && !overwrite) continue;
            if (reviewing) imageRunner.discardPending(r); // tạo lại → bỏ ảnh đang chờ duyệt
            r.setImageStatus(ScriptReference.ImageStatus.PENDING);
            r.setImageAttempt(0);
            r.setImageError(null);
            references.save(r);
            n++;
        }
        if (n == 0) throw new IllegalStateException(wanted == null ? "Mọi mã đã có ảnh hoặc đang chờ duyệt – chọn “Tạo lại” ở từng mã nếu muốn tạo ảnh mới" : "Không có mã nào cần tạo");
        return references(id);
    }

    /** Duyệt ảnh AI của mã: đổi tên thành MÃ.đuôi trong thư mục ảnh tham chiếu (tự tạo), gán cho mã. */
    public ScriptReferenceDto approveReference(Long id, String code) throws IOException {
        imageRunner.approve(reference(id, code).getId());
        return referenceDto(id, code);
    }

    /** Bỏ ảnh AI chờ duyệt của mã (xoá file), mã giữ ảnh cũ nếu có. */
    public ScriptReferenceDto rejectReference(Long id, String code) {
        imageRunner.reject(reference(id, code).getId());
        return referenceDto(id, code);
    }

    /** Duyệt mọi ảnh đang chờ duyệt của dự án. Trả về danh sách mã lỗi (nếu có) qua log, vẫn duyệt các mã khác. */
    public List<ScriptReferenceDto> approveAll(Long id) {
        project(id);
        List<String> failed = new ArrayList<>();
        for (ScriptReference r : references.findByProjectIdOrderByKindAscCodeAsc(id)) {
            if (r.getImageStatus() != ScriptReference.ImageStatus.REVIEW) continue;
            try {
                imageRunner.approve(r.getId());
            } catch (Exception e) {
                log.warn("Approve {} failed: {}", r.getCode(), e.getMessage());
                failed.add(r.getCode());
            }
        }
        if (!failed.isEmpty()) throw new IllegalStateException("Không duyệt được: " + String.join(", ", failed) + " – xem lỗi ở từng mã");
        return references(id);
    }

    private ScriptReferenceDto referenceDto(Long id, String code) {
        return references(id).stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
    }

    /** Gợi ý ghép file trong thư mục ảnh người dùng chọn với mã tham chiếu (chưa tải nội dung lên). */
    public List<ark.dto.ScriptFileMatchDto> matchFiles(Long id, List<ark.dto.ScriptFileMatchRequest.FileItem> files) {
        project(id);
        return ScriptFileMatcher.match(references.findByProjectIdOrderByKindAscCodeAsc(id),
                files.stream().map(f -> new ScriptFileMatcher.FileIn(f.getKey(), f.getPath())).toList());
    }

    /** Huỷ các ảnh còn chờ trong hàng đợi (ảnh đang tạo dở vẫn chạy cho xong). */
    @Transactional
    public List<ScriptReferenceDto> stopGenerating(Long id) {
        for (ScriptReference r : references.findByProjectIdOrderByKindAscCodeAsc(id)) {
            if (r.getImageStatus() == ScriptReference.ImageStatus.PENDING) {
                r.setImageStatus(ScriptReference.ImageStatus.NONE);
                references.save(r);
            }
        }
        return references(id);
    }

    /**
     * Model ngôn ngữ (Dola-Seed) viết prompt ảnh cho mã từ mô tả trong kịch bản.
     * save = true: lưu luôn (đánh dấu đã sửa – nhập lại Excel không ghi đè); false: chỉ trả về để người dùng xem / sửa.
     */
    public Map<String, Object> aiPrompt(Long id, String code, boolean save) throws IOException, InterruptedException {
        if (config.apiKey().isEmpty()) throw new IllegalStateException("Chưa có API key – nhập trong trang Cài đặt trước");
        ScriptProject p = project(id);
        String prompt = promptWriter.write(p, reference(id, code));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prompt", prompt);
        if (save) {
            ScriptReference r = reference(id, code); // đọc lại: trong lúc chờ model có thể đã đổi
            r.setImagePrompt(prompt);
            r.setPromptEdited(true);
            references.save(r);
            out.put("reference", referenceDto(id, code));
        }
        return out;
    }

    @Transactional
    public ScriptReferenceDto updateReferencePrompt(Long id, String code, String prompt, boolean generate) {
        ScriptReference r = reference(id, code);
        r.setImagePrompt(prompt.trim());
        r.setPromptEdited(true);
        references.save(r);
        if (generate) generateReferences(id, List.of(code), true);
        return references(id).stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
    }

    @Transactional
    public ScriptReferenceDto setReference(Long id, String code, Long fileId) {
        ScriptReference r = reference(id, code);
        if (fileId != null) {
            GeneratedFile f = fileLibrary.get(fileId);
            if (f.getKind() != MediaKind.IMAGE) throw new IllegalArgumentException(f.getName() + " không phải ảnh");
        }
        if (r.getImageStatus() == ScriptReference.ImageStatus.REVIEW) { // gán ảnh tay → bỏ ảnh AI đang chờ duyệt
            imageRunner.discardPending(r);
            r.setImageStatus(ScriptReference.ImageStatus.NONE);
        }
        r.setFileId(fileId);
        r.setBlockedReason(null);
        references.save(r);
        return references(id).stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
    }

    /** Tải ảnh từ máy lên cho một mã: lưu thành output/assets/&lt;dự án&gt;/&lt;mã&gt;.&lt;đuôi&gt; và gán luôn. */
    @Transactional
    public ScriptReferenceDto uploadReference(Long id, String code, String fileName, InputStream in) throws IOException {
        ScriptProject p = project(id);
        reference(id, code);
        String ext = fileName == null || !fileName.contains(".") ? "" : fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_EXT.contains(ext)) throw new IllegalArgumentException("Chỉ nhận ảnh jpg, png, webp, bmp");
        Path dir = fileLibrary.root().resolve("assets").resolve(p.getFolder());
        Files.createDirectories(dir);
        Path target = dir.resolve(code + "." + ext);
        for (int v = 2; Files.exists(target); v++) target = dir.resolve(code + "_v" + v + "." + ext);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        GeneratedFile f = fileLibrary.record(new SavedOutput(target, null), null, "Ảnh tham chiếu " + code + " – " + p.getName(), null, null);
        return setReference(id, code, f.getId());
    }

    /**
     * Gán tự động: mã chưa có ảnh được ghép với ảnh trong thư viện có tên file trùng mã
     * (CHAR_YVO_NAVYTEE_01.png, CHAR_YVO_NAVYTEE_01_v2.jpg…). Ảnh mới nhất được ưu tiên.
     */
    @Transactional
    public int autoMatchReferences(Long id) {
        List<GeneratedFile> images = files.findByKindOrderByCreatedAtDesc(MediaKind.IMAGE);
        int n = 0;
        for (ScriptReference r : references.findByProjectIdOrderByKindAscCodeAsc(id)) {
            if (r.isAwaitingImage() || (r.getFileId() != null && runner.fileUsable(r.getFileId()))) continue;
            String code = r.getCode();
            GeneratedFile hit = images.stream().filter(f -> nameMatchesCode(f.getName(), code))
                    .filter(f -> Files.isRegularFile(fileLibrary.pathOf(f))).findFirst().orElse(null);
            if (hit != null) {
                r.setFileId(hit.getId());
                references.save(r);
                n++;
            }
        }
        return n;
    }

    static boolean nameMatchesCode(String name, String code) {
        return AssetLibraryService.nameMatchesCode(name, code);
    }

    /**
     * Ghép asset Virtual Portrait (Active, mọi asset group) với mã theo tên asset hoặc tên group – vd asset upload trên
     * console tên CHAR_AUD_PINKSUIT_01.jpg. Asset được tải về thư viện (giữ asset ID) rồi gán cho mã → clip gửi asset://.
     * Mã đã có ảnh với asset Active thì giữ nguyên. Trả về số mã đã ghép.
     */
    public int matchVpAssets(Long id) throws IOException, InterruptedException {
        ScriptProject p = project(id);
        List<Map<String, Object>> assets = assetLibrary.activeAssetsCached(true);
        log.info("Ghép asset VP cho dự án {}: {} asset Active trên BytePlus", id, assets.size());
        if (assets.isEmpty()) return 0;
        Path dir = fileLibrary.root().resolve("assets").resolve(p.getFolder());
        int n = 0;
        for (ScriptReference r : references.findByProjectIdOrderByKindAscCodeAsc(id)) {
            if (r.isAwaitingImage()) continue; // đang tạo / chờ duyệt ảnh AI
            if (r.getFileId() != null && files.findById(r.getFileId())
                    .map(f -> f.getAssetId() != null && "Active".equals(f.getAssetStatus())).orElse(false)) continue;
            Map<String, Object> hit = AssetLibraryService.findAssetFor(assets, r.getCode());
            if (hit == null) continue;
            GeneratedFile f = assetLibrary.fileForAsset(hit, dir, r.getCode());
            log.info("  {} → asset {}", r.getCode(), ark.integration.byteplus.Json.str(hit, "Id"));
            r.setFileId(f.getId());
            r.setBlockedReason(null);
            references.save(r);
            n++;
        }
        return n;
    }

    private ScriptReference reference(Long id, String code) {
        return references.findByProjectIdAndCode(id, code)
                .orElseThrow(() -> new NoSuchElementException("Dự án không có mã tham chiếu " + code));
    }

    // ---------- chuyển đổi DTO ----------

    private Map<String, ScriptReference> refMap(Long id) {
        return references.findByProjectIdOrderByKindAscCodeAsc(id).stream()
                .collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
    }

    public ScriptProjectDto toDto(ScriptProject p) {
        Map<String, ScriptReference> refs = refMap(p.getId());
        ScriptRunner.RefCheck check = runner.refCheck(refs);
        List<ScriptClip> all = clips.findByProjectIdOrderBySeq(p.getId());
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("TOTAL", (long) all.size());
        for (ScriptClip.Status s : ScriptClip.Status.values()) counts.put(s.name(), 0L);
        int blocked = 0, warn = 0;
        for (ScriptClip c : all) {
            counts.merge(c.getStatus().name(), 1L, Long::sum);
            if (c.getStatus() == ScriptClip.Status.PENDING && !check.missing(c).isEmpty()) blocked++;
            if (c.getWarnings() != null) warn++;
        }
        Set<String> usedCodes = all.stream().flatMap(c -> c.refList().stream()).collect(Collectors.toSet());
        // Thiếu ảnh = chưa có ảnh dùng được và cũng không đang tạo / chờ duyệt
        int missing = (int) refs.values().stream()
                .filter(r -> usedCodes.contains(r.getCode()) && check.noFile(r.getCode()) && !r.isAwaitingImage()).count();
        return ScriptProjectDto.builder().id(p.getId()).name(p.getName()).sourceFile(p.getSourceFile()).folder(p.getFolder())
                .model(p.getModel()).resolution(p.getResolution()).ratio(p.getRatio()).generateAudio(p.isGenerateAudio())
                .maxParallel(p.getMaxParallel()).maxAttempts(p.getMaxAttempts()).status(p.getStatus())
                .imageModel(ScriptImageRunner.model(p).id())
                .imageSize(p.getImageSize() == null ? ScriptImageRunner.model(p).defaultSize() : p.getImageSize())
                .promptModel(p.getPromptModel())
                .imagesPending((int) refs.values().stream().filter(r -> r.getImageStatus() == ScriptReference.ImageStatus.PENDING
                        || r.getImageStatus() == ScriptReference.ImageStatus.GENERATING).count())
                .imagesFailed((int) refs.values().stream().filter(r -> r.getImageStatus() == ScriptReference.ImageStatus.FAILED).count())
                .imagesReview((int) refs.values().stream().filter(r -> r.getImageStatus() == ScriptReference.ImageStatus.REVIEW).count())
                .autoApproveImages(p.isAutoApproveImages()).refSaveMode(p.getRefSaveMode()).refSaveDir(p.getRefSaveDir())
                .pausedReason(p.getPausedReason()).cooldownUntil(p.getCooldownUntil()).createdAt(p.getCreatedAt())
                .counts(counts).refsTotal(refs.size()).refsMissing(missing).blockedClips(blocked).warningClips(warn)
                .onlySelected(p.isOnlySelected())
                .selectedPending((int) all.stream().filter(c -> c.isRunSelected() && (c.getStatus() == ScriptClip.Status.PENDING
                        || c.getStatus() == ScriptClip.Status.GENERATING)).count())
                .build();
    }

    private ScriptClipDto toDto(ScriptClip c, ScriptRunner.RefCheck check) {
        String err = c.getError();
        if (c.getStatus() == ScriptClip.Status.GENERATING && runner.lastLog(c.getId()) != null) err = runner.lastLog(c.getId());
        return ScriptClipDto.builder().id(c.getId()).code(c.getCode()).seq(c.getSeq()).sceneTitle(c.getSceneTitle())
                .timecode(c.getTimecode()).duration(c.getDuration()).refCodes(c.refList())
                .missingRefs(check.missing(c)).prompt(c.getPrompt()).promptVi(c.getPromptVi())
                .dialogue(c.getDialogue()).note(c.getNote()).warnings(c.warningList()).status(c.getStatus())
                .attempt(c.getAttempt()).taskId(c.getTaskId()).error(err).file(fileDto(c.getFileId()))
                .startedAt(c.getStartedAt()).finishedAt(c.getFinishedAt())
                .blockAnalysis(parseAnalysis(c.getBlockAnalysis())).analyzing(blockAnalyzer.isAnalyzing(c.getId()))
                .runSelected(c.isRunSelected()).build();
    }

    private static Object parseAnalysis(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return ark.integration.byteplus.Json.parse(json);
        } catch (IllegalArgumentException e) {
            return Map.of("summary", json);
        }
    }

    private GeneratedFileDto fileDto(Long fileId) {
        if (fileId == null) return null;
        return files.findById(fileId).map(fileLibrary::toDto).orElse(null);
    }

    private static Integer numberOf(String code) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(code);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Chỉ mã thuần số (019 khớp "19", nhưng 019A thì không). */
    private static Integer numberOfExact(String code) {
        return code.matches("\\d+") ? Integer.valueOf(code) : null;
    }
}
