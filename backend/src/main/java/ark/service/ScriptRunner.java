package ark.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.entity.GeneratedFile;
import ark.entity.ScriptClip;
import ark.entity.ScriptProject;
import ark.entity.ScriptReference;
import ark.exception.GlobalExceptionHandler;
import ark.integration.byteplus.ArkClient;
import ark.integration.byteplus.ArkException;
import ark.integration.byteplus.Json;
import ark.integration.byteplus.Models;
import ark.integration.byteplus.SavedOutput;
import ark.integration.byteplus.VideoGenerator;
import ark.repository.ScriptClipRepository;
import ark.repository.ScriptProjectRepository;
import ark.repository.ScriptReferenceRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bộ chạy hàng đợi kịch bản (giống Flow Batch Studio, nhưng chạy ngay trong Spring):
 * cứ 3 giây lấy clip PENDING của các dự án đang RUNNING (ưu tiên clip làm lại, rồi theo thứ tự kịch bản),
 * chỉ lấy clip đã đủ ảnh tham chiếu, giữ tối đa maxParallel clip đang tạo mỗi dự án.
 * <p>
 * Mỗi clip: gửi task Seedance → theo dõi tới khi xong → tải video về output/videos/&lt;dự án&gt;/&lt;mã clip&gt;.mp4.
 * Xử lý lỗi:
 * <ul>
 * <li>429 / quota: đưa clip lại hàng đợi (không tính lần thử), dự án nghỉ 60s;</li>
 * <li>401/403, model chưa mở, chưa có API key: đưa clip lại hàng đợi và tạm dừng dự án (cần người xử lý);</li>
 * <li>5xx, mạng, task lỗi nội bộ: thử lại tới maxAttempts; nếu task đã tạo thì lần sau chỉ theo dõi tiếp, không gửi lại;</li>
 * <li>nội dung bị từ chối, tham số sai: FAILED ngay.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptRunner {
    private static final long COOLDOWN_SECONDS = 60;

    private final Config config;
    private final ScriptProjectRepository projects;
    private final ScriptClipRepository clips;
    private final ScriptReferenceRepository references;
    private final FileLibraryService fileLibrary;
    private final AssetLibraryService assetLibrary;
    private final ScriptBlockAnalyzer blockAnalyzer;

    /** Tiền tố lý do chặn khi chính Virtual Portrait từ chối ảnh → cần người thay ảnh. */
    public static final String VP_FAILED = "Virtual Portrait từ chối ảnh";

    /** Clip phải chờ vì ảnh tham chiếu chưa dùng được (VP từ chối…) – đưa clip về hàng đợi, không tính lỗi. */
    private static final class RefBlocked extends Exception {
        RefBlocked(String m) {
            super(m);
        }
    }

    private final ExecutorService pool = Executors.newCachedThreadPool(); // số luồng = tổng "song song" của các dự án
    /** clipId → projectId của các clip đang chạy trong tiến trình này. */
    private final Map<Long, Long> active = new ConcurrentHashMap<>();
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    /** Log ngắn gần nhất của từng clip đang chạy (hiện trên giao diện). */
    private final Map<Long, String> lastLog = new ConcurrentHashMap<>();

    /** Backend khởi động lại: clip đang tạo dở quay về hàng đợi (giữ taskId → chỉ theo dõi tiếp, không gửi lại). */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int c = clips.updateStatus(ScriptClip.Status.GENERATING, ScriptClip.Status.PENDING);
        int p = projects.updateStatus(ScriptProject.Status.RUNNING, ScriptProject.Status.PAUSED,
                "Backend vừa khởi động lại – bấm Chạy để tiếp tục");
        if (c + p > 0) log.info("Script runner recovered {} clip(s), paused {} project(s)", c, p);
    }

    public boolean isActive(Long clipId) {
        return active.containsKey(clipId);
    }

    public String lastLog(Long clipId) {
        return lastLog.get(clipId);
    }

    /** @return true nếu clip đang chạy và đã được yêu cầu dừng */
    public boolean requestCancel(Long clipId) {
        if (!active.containsKey(clipId)) return false;
        cancelRequested.add(clipId);
        return true;
    }

    @Scheduled(fixedDelay = 1500, initialDelay = 5000)
    public synchronized void dispatch() {
        for (ScriptProject p : projects.findByStatus(ScriptProject.Status.RUNNING)) {
            try {
                dispatch(p);
            } catch (Exception e) {
                log.error("Dispatch failed for script project {}", p.getId(), e);
            }
        }
    }

    private void dispatch(ScriptProject p) {
        if (p.getCooldownUntil() != null && p.getCooldownUntil().isAfter(Instant.now())) return;
        long running = active.values().stream().filter(p.getId()::equals).count();
        Map<String, ScriptReference> refs = references.findByProjectIdOrderByKindAscCodeAsc(p.getId()).stream()
                .collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
        List<ScriptClip> queue = clips.findByProjectIdAndStatusOrderByRunPriorityDescSeqAsc(p.getId(), ScriptClip.Status.PENDING);
        int blocked = 0;
        Set<String> missingCodes = new java.util.TreeSet<>();
        RefCheck check = new RefCheck(refs);
        for (ScriptClip c : queue) {
            if (running >= p.getMaxParallel()) return;
            if (active.containsKey(c.getId())) continue;
            List<String> missing = check.missing(c);
            if (!missing.isEmpty()) {
                blocked++;
                missingCodes.addAll(missing);
                continue;
            }
            active.put(c.getId(), p.getId());
            if (clips.claim(c.getId(), Instant.now()) == 0) { // vừa bị huỷ / sửa
                active.remove(c.getId());
                continue;
            }
            lastLog.put(c.getId(), "Đang chuẩn bị…");
            running++;
            Long id = c.getId();
            pool.submit(() -> run(id));
        }
        if (running > 0) return;
        // Không còn gì để chạy
        ScriptProject fresh = projects.findById(p.getId()).orElse(null);
        if (fresh == null || fresh.getStatus() != ScriptProject.Status.RUNNING) return;
        if (blocked > 0 && references.countByProjectIdAndImageStatusIn(p.getId(),
                Set.of(ScriptReference.ImageStatus.PENDING, ScriptReference.ImageStatus.GENERATING, ScriptReference.ImageStatus.REVIEW)) > 0) {
            return; // ảnh tham chiếu đang được tạo / chờ duyệt – clip sẽ chạy khi ảnh được duyệt
        }
        if (blocked > 0) {
            fresh.setStatus(ScriptProject.Status.PAUSED);
            List<String> blockedCodes = refs.values().stream().filter(check::blocked).map(ScriptReference::getCode)
                    .filter(missingCodes::contains).sorted().toList();
            if (!blockedCodes.isEmpty()) {
                String tos = assetLibrary.tosUnavailable();
                fresh.setPausedReason(blocked + " clip chờ ảnh bị chặn: " + String.join(", ", blockedCodes)
                        + (assetLibrary.vpReady() && tos != null ? ". " + tos + " – hoặc thay bằng ảnh tạo AI (“Tạo lại AI”), rồi bấm Chạy."
                        : assetLibrary.vpReady() ? ". Virtual Portrait đã từ chối các ảnh này – thay ảnh khác hoặc bấm “Tạo lại AI”, rồi bấm Chạy."
                        : ". Nhập AK/SK (Cài đặt) và chọn asset group (trang Virtual Portrait) để app tự đẩy ảnh lên Virtual Portrait, rồi bấm Chạy."));
                projects.save(fresh);
                return;
            }
            String list = String.join(", ", missingCodes);
            if (list.length() > 380) list = list.substring(0, 380) + "… (" + missingCodes.size() + " mã)";
            fresh.setPausedReason(blocked + " clip đang chờ ảnh tham chiếu – thiếu: " + list);
        } else {
            long ok = clips.countByProjectIdAndStatus(p.getId(), ScriptClip.Status.SUCCEEDED);
            long failed = clips.countByProjectIdAndStatus(p.getId(), ScriptClip.Status.FAILED);
            fresh.setStatus(ScriptProject.Status.DONE);
            fresh.setPausedReason("Đã chạy xong: " + ok + " thành công" + (failed > 0 ? ", " + failed + " lỗi" : ""));
        }
        projects.save(fresh);
    }

    /** Mã tham chiếu của clip chưa gán ảnh hoặc ảnh đã bị xoá khỏi máy. */
    public List<String> missingRefs(ScriptClip c, Map<String, ScriptReference> refs) {
        return new RefCheck(refs).missing(c);
    }

    public RefCheck refCheck(Map<String, ScriptReference> refs) {
        return new RefCheck(refs);
    }

    /**
     * Kiểm tra mã ảnh cho nhiều clip một lượt: mỗi mã chỉ tra DB / ổ đĩa một lần (thay vì một lần cho mỗi clip dùng mã đó).
     * Dùng trong một lần điều phối / một request, không giữ lâu.
     */
    public final class RefCheck {
        private final Map<String, ScriptReference> refs;
        private final Map<String, Boolean> noFile = new java.util.HashMap<>();
        private final Map<String, Boolean> unusable = new java.util.HashMap<>();
        /** File của mọi mã, tải một lần bằng một truy vấn (thay vì một truy vấn cho mỗi mã). */
        private final Map<Long, GeneratedFile> files;

        RefCheck(Map<String, ScriptReference> refs) {
            this.refs = refs;
            List<Long> ids = refs.values().stream().map(ScriptReference::getFileId).filter(java.util.Objects::nonNull).distinct().toList();
            this.files = ids.isEmpty() ? Map.of() : fileLibrary.existing(ids).stream()
                    .collect(Collectors.toMap(GeneratedFile::getId, f -> f, (a, b) -> a));
        }

        public List<String> missing(ScriptClip c) {
            List<String> out = new ArrayList<>();
            for (String code : c.refList()) if (unusable(code)) out.add(code);
            return out;
        }

        /** Chưa gán ảnh hoặc ảnh không còn dùng được. */
        public boolean noFile(String code) {
            return noFile.computeIfAbsent(code, k -> {
                ScriptReference r = refs.get(k);
                // Đang tạo / chờ duyệt ảnh mới → chưa dùng ảnh cũ
                return r == null || r.getFileId() == null || r.isAwaitingImage() || !fileUsable(files.get(r.getFileId()));
            });
        }

        /** Thiếu ảnh hoặc ảnh đang bị chặn. */
        public boolean unusable(String code) {
            return unusable.computeIfAbsent(code, k -> noFile(k) || isBlocked(refs.get(k), files.get(refs.get(k).getFileId())));
        }

        /** Như {@link ScriptRunner#isBlocked(ScriptReference)} nhưng dùng file đã tải sẵn. */
        public boolean blocked(ScriptReference r) {
            return isBlocked(r, r.getFileId() == null ? null : files.get(r.getFileId()));
        }
    }

    /** Ảnh bị chặn và chưa được đẩy Virtual Portrait (asset Active thì gửi asset:// → không còn bị chặn). */
    public boolean isBlocked(ScriptReference r) {
        if (r.getBlockedReason() == null) return false;
        return isBlocked(r, r.getFileId() == null ? null : fileLibrary.existing(List.of(r.getFileId())).stream().findFirst().orElse(null));
    }

    private boolean isBlocked(ScriptReference r, GeneratedFile f) {
        if (r.getBlockedReason() == null) return false;
        try {
            if (f == null) return true;
            if (f.getAssetId() != null && "Active".equals(f.getAssetStatus())) return false;
            if (assetLibrary.cachedAssetFor(r.getCode()) != null) return false; // có asset VP trùng tên → gắn khi gửi clip
            if (r.getBlockedReason().startsWith(VP_FAILED)) return true;
            return !assetLibrary.canPush(f); // đẩy được VP (AK/SK + group + TOS / URL gốc) → app tự đẩy khi gửi clip
        } catch (Exception e) {
            return true;
        }
    }

    /** Ảnh gốc do Seedream 5.0 tạo, URL còn hạn → BytePlus tin cậy, không cần Virtual Portrait. */
    private static boolean trustedOutput(GeneratedFile f) {
        String m = f.getModel() == null ? "" : f.getModel();
        return f.isSourceUrlValid() && (m.startsWith("seedream-5") || m.startsWith("dola-seedream-5"));
    }

    /** Ảnh nhân vật lấy từ máy (hoặc ảnh từng bị chặn) cần đưa lên Virtual Portrait trước khi gửi Seedance. */
    private boolean needsVirtualPortrait(ScriptReference r, GeneratedFile f) {
        if (f.getAssetId() != null && "Active".equals(f.getAssetStatus())) return false;
        if (r.getBlockedReason() != null) return true;
        return r.getKind() == ScriptReference.Kind.CHARACTER && !trustedOutput(f);
    }

    /** Ảnh dùng được: asset Active hoặc file còn trên máy. */
    public boolean fileUsable(Long fileId) {
        try {
            return fileUsable(fileLibrary.get(fileId));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean fileUsable(GeneratedFile f) {
        if (f == null) return false;
        if (f.getAssetId() != null && "Active".equals(f.getAssetStatus())) return true;
        try {
            return Files.isRegularFile(fileLibrary.pathOf(f));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- chạy một clip ----------

    private static final class TaskFailed extends Exception {
        final String code;

        TaskFailed(String code, String message) {
            super(message);
            this.code = code == null ? "" : code;
        }
    }

    private void run(Long clipId) {
        Consumer<String> say = msg -> lastLog.put(clipId, msg);
        ScriptClip clip = null;
        ScriptProject project = null;
        try {
            clip = clips.findById(clipId).orElseThrow();
            project = projects.findById(clip.getProjectId()).orElseThrow();
            Models.VideoModel model = Models.video(project.getModel());
            ArkClient client = ArkClient.from(config);

            String taskId = clip.getTaskId();
            if (taskId == null) {
                VideoGenerator.Params p = params(clip, project, model, say);
                say.accept("Đang gửi task lên Seedance…");
                taskId = VideoGenerator.submit(client, p, say);
                String t = taskId;
                updateClip(clipId, c -> c.setTaskId(t));
            } else {
                say.accept("Theo dõi tiếp task " + taskId);
            }

            Map<String, Object> task = VideoGenerator.waitFor(client, taskId, say, () -> cancelRequested.contains(clipId));
            if (task == null) {
                try {
                    client.deleteVideoTask(taskId);
                } catch (Exception ignored) {
                    // task có thể đã xong – bỏ qua
                }
                finish(clipId, ScriptClip.Status.CANCELLED, "Đã dừng theo yêu cầu", true);
                return;
            }
            String status = Json.str(task, "status");
            if (!"succeeded".equals(status)) {
                throw new TaskFailed(Json.str(task, "error", "code"),
                        "Task " + status + (Json.str(task, "error", "message") != null
                                ? ": " + Json.str(task, "error", "code") + " – " + Json.str(task, "error", "message") : ""));
            }
            String url = Json.str(task, "content", "video_url");
            if (url == null) throw new IOException("Task thành công nhưng không có video_url");
            String fmt = Json.str(task, "output_format");
            Path target = freeTarget(project, clip.getCode(), fmt == null ? "mp4" : fmt);
            say.accept("Đang tải video…");
            client.download(url, target);
            GeneratedFile f = fileLibrary.record(new SavedOutput(target, url), model.id(), clip.getPrompt(), taskId, null);
            assetHeals.remove(clipId);
            updateClip(clipId, c -> {
                c.setStatus(ScriptClip.Status.SUCCEEDED);
                c.setFileId(f.getId());
                c.setError(null);
                c.setFinishedAt(Instant.now());
            });
        } catch (Exception e) {
            handleError(clipId, project, e);
        } finally {
            active.remove(clipId);
            cancelRequested.remove(clipId);
            lastLog.remove(clipId);
        }
    }

    /**
     * Có asset Virtual Portrait Active tên trùng mã (tên asset hoặc tên group, vd upload trên console) → tải về thư viện,
     * gán cho mã, bỏ đánh dấu chặn. Trả về file đã gắn asset, hoặc null nếu không có / lỗi.
     */
    private GeneratedFile linkVpAsset(ScriptProject project, ScriptReference r, Consumer<String> say) {
        if (!assetLibrary.hasKeys()) return null;
        try {
            Map<String, Object> hit = AssetLibraryService.findAssetFor(assetLibrary.activeAssetsCached(false), r.getCode());
            if (hit == null) return null;
            GeneratedFile f;
            synchronized (this) {
                f = assetLibrary.fileForAsset(hit, fileLibrary.root().resolve("assets").resolve(project.getFolder()), r.getCode());
                ScriptReference fresh = references.findById(r.getId()).orElse(r);
                fresh.setFileId(f.getId());
                fresh.setBlockedReason(null);
                references.save(fresh);
                r.setFileId(f.getId());
                r.setBlockedReason(null);
            }
            say.accept(r.getCode() + ": dùng asset Virtual Portrait asset://" + f.getAssetId());
            log.info("Mã {} gắn asset VP {}", r.getCode(), f.getAssetId());
            return f;
        } catch (Exception e) {
            log.warn("Không tìm / gắn được asset VP cho {}: {}", r.getCode(), GlobalExceptionHandler.describe(e));
            return null;
        }
    }

    private VideoGenerator.Params params(ScriptClip clip, ScriptProject project, Models.VideoModel model, Consumer<String> say)
            throws Exception {
        Map<String, ScriptReference> refs = references.findByProjectIdOrderByKindAscCodeAsc(project.getId()).stream()
                .collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
        List<String> images = new ArrayList<>();
        for (String code : clip.refList()) {
            ScriptReference r = refs.get(code);
            if (r == null || r.getFileId() == null) throw new IllegalArgumentException("Chưa gán ảnh cho mã " + code);
            GeneratedFile f = fileLibrary.get(r.getFileId());
            if (needsVirtualPortrait(r, f)) {
                GeneratedFile linked = linkVpAsset(project, r, say);
                if (linked != null) f = linked;
            }
            if (needsVirtualPortrait(r, f) && assetLibrary.vpReady()) {
                Long clipId = clip.getId();
                boolean ok;
                String tos = assetLibrary.canPush(f) ? null : assetLibrary.tosUnavailable();
                if (tos == null) {
                    say.accept("Đưa ảnh " + code + " lên Virtual Portrait (để BytePlus không chặn mặt người)…");
                    try {
                        ok = assetLibrary.ensureActiveAsset(f, m -> say.accept(code + ": " + m), () -> cancelRequested.contains(clipId));
                    } catch (IllegalStateException e) {
                        tos = assetLibrary.tosUnavailable();
                        if (tos == null) throw e;
                        ok = false;
                    }
                } else {
                    ok = false;
                }
                if (tos != null) {
                    // Không đẩy được VP vì TOS: ảnh chưa từng bị chặn → gửi thẳng (BytePlus chặn thì blockRefs đánh dấu sau)
                    if (r.getBlockedReason() != null) {
                        throw new RefBlocked("Chờ ảnh: " + code + " – " + r.getBlockedReason() + ". " + tos);
                    }
                    say.accept(code + ": không đẩy được Virtual Portrait (TOS chưa bật) – gửi ảnh trực tiếp");
                    images.add(imageRef(f, code));
                    continue;
                }
                f = fileLibrary.get(r.getFileId());
                if (!ok) {
                    if (cancelRequested.contains(clipId)) throw new InterruptedException("cancel");
                    String why = VP_FAILED + ": " + (f.getAssetError() == null ? "asset Failed" : f.getAssetError());
                    r.setBlockedReason(why.length() > 1000 ? why.substring(0, 1000) : why);
                    // Chỉ ghi cột blockedReason: entity r đọc từ đầu lượt gửi, người dùng có thể vừa gán ảnh khác
                    references.setBlockedReason(r.getId(), r.getBlockedReason());
                    throw new RefBlocked("Chờ ảnh: " + code + " – " + why + ". Thay ảnh khác hoặc bấm “Tạo lại AI”.");
                }
                if (r.getBlockedReason() != null) {
                    r.setBlockedReason(null);
                    references.setBlockedReason(r.getId(), null);
                }
            }
            images.add(imageRef(f, code));
        }
        VideoGenerator.Params p = new VideoGenerator.Params();
        p.model = model;
        p.mode = Models.VideoMode.OMNI;
        p.prompt = clip.getPrompt();
        p.refImages = images;
        p.resolution = project.getResolution();
        p.ratio = project.getRatio();
        p.duration = Math.max(model.minDuration(), Math.min(model.maxDuration(), clip.getDuration()));
        p.generateAudio = project.isGenerateAudio();
        p.watermark = false;
        return p;
    }

    /** Ảnh tham chiếu: ưu tiên asset:// (Virtual Portrait – tránh bị chặn mặt người thật), rồi URL gốc còn hạn, rồi file trên máy (base64). */
    private String imageRef(GeneratedFile f, String code) {
        if (f.getAssetId() != null && "Active".equals(f.getAssetStatus())) return "asset://" + f.getAssetId();
        if (f.isSourceUrlValid()) return f.getSourceUrl();
        Path p = fileLibrary.pathOf(f);
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("Ảnh của mã " + code + " (" + f.getName() + ") không còn trên máy");
        return p.toString();
    }

    /** output/videos/&lt;folder&gt;/&lt;mã&gt;.mp4; nếu đã có (làm lại) thì &lt;mã&gt;_v2.mp4, _v3… để không mất bản cũ. */
    private Path freeTarget(ScriptProject project, String code, String ext) throws IOException {
        Path dir = fileLibrary.root().resolve("videos").resolve(project.getFolder());
        Files.createDirectories(dir);
        Path t = dir.resolve(code + "." + ext);
        for (int v = 2; Files.exists(t); v++) t = dir.resolve(code + "_v" + v + "." + ext);
        return t;
    }

    // ---------- phân loại lỗi ----------

    private void handleError(Long clipId, ScriptProject project, Exception e) {
        String msg = GlobalExceptionHandler.describe(e);
        log.warn("Script clip {} error: {}", clipId, msg);
        if (e instanceof RefBlocked) {
            requeue(clipId, msg);
            return;
        }
        if (e instanceof InterruptedException && cancelRequested.contains(clipId)) {
            finish(clipId, ScriptClip.Status.CANCELLED, "Đã dừng theo yêu cầu", true);
            return;
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            requeue(clipId, "Bị ngắt khi backend tắt – sẽ chạy lại");
            return;
        }
        if (e instanceof TaskFailed tf) {
            String code = tf.code.toLowerCase(Locale.ROOT);
            if (isOutputModeration(tf.code)) {
                // Kiểm duyệt video / audio ĐẦU RA: mỗi lần tạo cho kết quả khác → gửi task mới, tính 1 lần thử
                outputModeration(clipId, project, msg, tf.code);
                return;
            }
            if (code.contains("sensitive") || code.contains("privacy") || code.contains("risk") || code.contains("policy")
                    || code.contains("invalidparameter") || code.contains("inputimage") || code.contains("inputtext")) {
                finish(clipId, ScriptClip.Status.FAILED, "Bị từ chối: " + msg + hint(code), true);
                blockAnalyzer.analyzeLater(clipId, tf.code, msg);
            } else {
                transient_(clipId, project, msg, true);
            }
            return;
        }
        if (e instanceof ArkException ae) {
            String code = ae.code() == null ? "" : ae.code().toLowerCase(Locale.ROOT);
            if (ae.status() == 429 || code.contains("ratelimit") || code.contains("quota") || code.contains("overload")) {
                requeue(clipId, "BytePlus giới hạn tốc độ (429) – chờ " + COOLDOWN_SECONDS + "s rồi gửi lại");
                if (project != null) updateProject(project.getId(), p -> p.setCooldownUntil(Instant.now().plusSeconds(COOLDOWN_SECONDS)));
                return;
            }
            if (code.contains("modelnotopen")) {
                requeue(clipId, msg);
                pause(project, modelNotOpen(project == null ? "?" : project.getModel(), "video"));
                return;
            }
            if (ae.status() == 401 || ae.status() == 403 || code.contains("authentication")
                    || code.contains("accessdenied") || code.contains("overdue") || code.contains("apikey")) {
                requeue(clipId, msg);
                pause(project, "Lỗi tài khoản / quyền: " + msg + " – sửa trong Cài đặt hoặc bật model trên console rồi bấm Chạy");
                return;
            }
            if (ae.status() >= 500) {
                transient_(clipId, project, msg, false);
                return;
            }
            // asset:// đã bị xoá trên BytePlus (xoá group / dọn thư viện trên console) nhưng DB vẫn ghi Active
            List<String> gone = missingAssets(ae.getMessage());
            if (!gone.isEmpty() && assetHeals.merge(clipId, 1, Integer::sum) <= MAX_ASSET_HEALS) {
                forgetAssets(gone);
                requeue(clipId, "Asset Virtual Portrait " + String.join(", ", gone)
                        + " đã bị xoá trên BytePlus – app bỏ asset cũ, đẩy lại ảnh trên máy rồi gửi lại clip");
                return;
            }
            if (code.contains("privacy") || code.contains("realperson")) {
                List<String> codes = blockRefs(clipId, ae.getMessage());
                if (!codes.isEmpty()) {
                    requeue(clipId, "Chờ ảnh: " + String.join(", ", codes) + " có mặt người thật, BytePlus không nhận ảnh gửi trực tiếp");
                    return;
                }
            }
            finish(clipId, ScriptClip.Status.FAILED, msg + hint(code), true);
            blockAnalyzer.analyzeLater(clipId, ae.code(), msg);
            return;
        }
        if (e instanceof IllegalStateException) { // chưa có API key…
            requeue(clipId, msg);
            pause(project, msg);
            return;
        }
        if (e instanceof IllegalArgumentException) {
            finish(clipId, ScriptClip.Status.FAILED, msg, true);
            return;
        }
        // Lỗi mạng / IO khác: thử lại, giữ taskId để chỉ theo dõi tiếp
        transient_(clipId, project, msg, false);
    }

    private static final java.util.regex.Pattern MISSING_ASSET =
            java.util.regex.Pattern.compile("asset (asset-[0-9A-Za-z-]+) is not found", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** Mỗi clip tự sửa asset mất tối đa vài lần (tránh lặp mãi nếu asset mới cũng bị từ chối). */
    private static final int MAX_ASSET_HEALS = 3;
    private final Map<Long, Integer> assetHeals = new ConcurrentHashMap<>();

    /** "The specified asset asset-…-mh7gc is not found" → các asset ID BytePlus không còn. */
    static List<String> missingAssets(String message) {
        List<String> out = new ArrayList<>();
        if (message == null) return out;
        java.util.regex.Matcher m = MISSING_ASSET.matcher(message);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /**
     * Quên asset đã bị xoá: file ảnh giữ nguyên trên máy nhưng không còn asset → lần gửi sau app ghép asset khác cùng tên
     * (nếu có) hoặc đẩy lại ảnh lên Virtual Portrait để có asset mới, rồi mới gửi asset://.
     */
    private synchronized void forgetAssets(List<String> assetIds) {
        assetLibrary.dropFromCache(assetIds);
        for (String id : assetIds) {
            for (GeneratedFile f : fileLibrary.byAssetId(id)) {
                f.setAssetId(null);
                f.setAssetStatus(null);
                f.setAssetError("Asset " + id + " đã bị xoá trên BytePlus");
                fileLibrary.save(f);
                log.info("Asset {} no longer exists on BytePlus – cleared from file {}", id, f.getName());
            }
        }
    }

    private static final java.util.regex.Pattern CONTENT_IDX = java.util.regex.Pattern.compile("content\\[(\\d+)]");

    /**
     * "input image 'content[2]' 'content[3]' may contain real person" → content[0] là prompt, content[i] là Ảnh i
     * → đánh dấu các mã đó bị chặn để các clip khác dùng chung mã đứng chờ thay vì lỗi lần lượt.
     */
    private List<String> blockRefs(Long clipId, String message) {
        ScriptClip c = clips.findById(clipId).orElse(null);
        if (c == null || message == null) return List.of();
        List<String> refList = c.refList();
        List<String> codes = new ArrayList<>();
        java.util.regex.Matcher m = CONTENT_IDX.matcher(message);
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            if (i >= 1 && i <= refList.size() && !codes.contains(refList.get(i - 1))) codes.add(refList.get(i - 1));
        }
        for (String code : codes) {
            references.findByProjectIdAndCode(c.getProjectId(), code).ifPresent(r -> references.setBlockedReason(r.getId(),
                    "Ảnh có mặt người thật – BytePlus chặn khi gửi trực tiếp (clip " + c.getCode() + ")"));
        }
        return codes;
    }

    /** Hướng dẫn khi tài khoản chưa bật model (HTTP 404 ModelNotOpen). */
    public static String modelNotOpen(String modelId, String what) {
        return "Tài khoản chưa bật model " + what + " " + modelId + ". Cách sửa: vào console.byteplus.com → ModelArk → "
                + "Model activation → tìm " + modelId + " → Activate rồi bấm Chạy lại; hoặc mở “Cấu hình chạy” chọn model "
                + "tài khoản đã bật (vd Seedance 2.0 mini) → Lưu cấu hình → Chạy.";
    }

    private static String hint(String code) {
        if (code.contains("privacy") || code.contains("real")) {
            return " → ảnh có mặt người thật: đẩy ảnh nhân vật vào Virtual Portrait để dùng asset:// rồi làm lại clip";
        }
        return "";
    }

    /**
     * OutputVideoSensitiveContentDetected / OutputAudioSensitiveContentDetected: video hoặc audio vừa tạo bị kiểm duyệt chặn.
     * Kết quả mỗi lần tạo khác nhau nên thử lại có thể qua; bản quyền (.PolicyViolation) hay giả mạo giấy tờ (.DeepFake)
     * thì lặp lại y hệt → không tính là kiểm duyệt ngẫu nhiên.
     */
    static boolean isOutputModeration(String code) {
        if (code == null) return false;
        String c = code.toLowerCase(Locale.ROOT);
        return (c.startsWith("outputvideosensitivecontentdetected") || c.startsWith("outputaudiosensitivecontentdetected"))
                && !c.contains(".");
    }

    /** Gợi ý sửa khi kiểm duyệt đầu ra chặn mãi. */
    static String outputModerationHint(String code) {
        boolean audio = code != null && code.toLowerCase(Locale.ROOT).startsWith("outputaudio");
        return audio
                ? " → audio tạo ra bị chặn: sửa lời thoại / mô tả âm thanh trong prompt, hoặc tắt “Tạo âm thanh” trong Cấu hình chạy rồi làm lại clip"
                : " → video tạo ra bị kiểm duyệt chặn: làm dịu chi tiết bạo lực / hở hang / nhạy cảm trong prompt (hoặc ghi chú khi Làm lại) rồi chạy lại";
    }

    private void outputModeration(Long clipId, ScriptProject project, String msg, String code) {
        int max = project == null ? 3 : project.getMaxAttempts();
        updateClip(clipId, c -> {
            c.setAttempt(c.getAttempt() + 1);
            c.setTaskId(null);
            if (c.getAttempt() < max) {
                c.setStatus(ScriptClip.Status.PENDING);
                c.setError("Kết quả bị kiểm duyệt chặn (lần " + c.getAttempt() + "/" + max + "), tạo lại task mới: " + msg);
            } else {
                c.setStatus(ScriptClip.Status.FAILED);
                c.setError("Bị kiểm duyệt chặn cả " + max + " lần: " + msg + outputModerationHint(code));
                c.setFinishedAt(Instant.now());
            }
        });
        if (clips.findById(clipId).map(c -> c.getStatus() == ScriptClip.Status.FAILED).orElse(false)) {
            blockAnalyzer.analyzeLater(clipId, code, msg); // chặn hẳn → chỉ ra đoạn nghi vấn bằng tiếng Việt
        }
    }

    private void transient_(Long clipId, ScriptProject project, String msg, boolean taskDead) {
        int max = project == null ? 3 : project.getMaxAttempts();
        updateClip(clipId, c -> {
            c.setAttempt(c.getAttempt() + 1);
            if (taskDead) c.setTaskId(null);
            if (c.getAttempt() < max) {
                c.setStatus(ScriptClip.Status.PENDING);
                c.setError("Lỗi tạm thời (lần " + c.getAttempt() + "/" + max + "), sẽ thử lại: " + msg);
            } else {
                c.setStatus(ScriptClip.Status.FAILED);
                c.setError("Hết " + max + " lần thử: " + msg);
                c.setFinishedAt(Instant.now());
            }
        });
    }

    /** Đưa clip lại hàng đợi, không tính lần thử; giữ taskId để lần sau theo dõi tiếp task cũ thay vì gửi lại. */
    private void requeue(Long clipId, String msg) {
        updateClip(clipId, c -> {
            c.setStatus(ScriptClip.Status.PENDING);
            c.setError(msg);
        });
    }

    private void finish(Long clipId, ScriptClip.Status status, String error, boolean clearTask) {
        assetHeals.remove(clipId); // chạy lại bằng tay → được tự sửa asset mất từ đầu
        updateClip(clipId, c -> {
            c.setStatus(status);
            c.setError(error);
            c.setFinishedAt(Instant.now());
            if (clearTask) c.setTaskId(null);
        });
    }

    private void pause(ScriptProject project, String reason) {
        if (project == null) return;
        updateProject(project.getId(), p -> {
            if (p.getStatus() == ScriptProject.Status.RUNNING) {
                p.setStatus(ScriptProject.Status.PAUSED);
                p.setPausedReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
            }
        });
    }

    private synchronized void updateClip(Long id, Consumer<ScriptClip> change) {
        clips.findById(id).ifPresent(c -> {
            change.accept(c);
            if (c.getError() != null && c.getError().length() > 2000) c.setError(c.getError().substring(0, 2000));
            clips.save(c);
        });
    }

    private synchronized void updateProject(Long id, Consumer<ScriptProject> change) {
        projects.findById(id).ifPresent(p -> {
            change.accept(p);
            projects.save(p);
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
