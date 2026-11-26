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
    private final BotsdService botsd;
    private final Api888Service api888;

    /** taskId của clip chạy qua 888API có tiền tố này. */
    static final String API888_PREFIX = "888:";

    /** taskId của clip chạy qua BOTSD có tiền tố này (phân biệt với task BytePlus). */
    static final String BOTSD_PREFIX = "botsd:";
    /** Gửi task BOTSD lần lượt: lỗi mạng lúc gửi thì so lịch sử trước/sau để biết task đã được nhận chưa (tránh trừ Point 2 lần). */
    private final Object botsdCreateLock = new Object();

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
        RefCheck check = new RefCheck(refs, p.sendsRawImages());
        for (ScriptClip c : queue) {
            if (running >= p.getMaxParallel()) return;
            if (active.containsKey(c.getId())) continue;
            if (p.isOnlySelected() && !c.isRunSelected()) continue; // chỉ chạy clip / cảnh đã chọn
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
            List<String> foreign = missingCodes.stream().filter(check::foreign).toList();
            if (!foreign.isEmpty()) {
                fresh.setPausedReason(blocked + " clip chờ ảnh: mã " + String.join(", ", foreign)
                        + " đang trỏ tới ảnh của dự án khác – vào tab Ảnh tham chiếu chọn lại ảnh cho dự án này, rồi bấm Chạy.");
                projects.save(fresh);
                return;
            }
            String list = String.join(", ", missingCodes);
            if (list.length() > 380) list = list.substring(0, 380) + "… (" + missingCodes.size() + " mã)";
            fresh.setPausedReason(blocked + " clip đang chờ ảnh tham chiếu – thiếu: " + list);
        } else if (fresh.isOnlySelected()) {
            finishSelected(fresh);
            return;
        } else {
            long ok = clips.countByProjectIdAndStatus(p.getId(), ScriptClip.Status.SUCCEEDED);
            long failed = clips.countByProjectIdAndStatus(p.getId(), ScriptClip.Status.FAILED);
            fresh.setStatus(ScriptProject.Status.DONE);
            fresh.setPausedReason("Đã chạy xong: " + ok + " thành công" + (failed > 0 ? ", " + failed + " lỗi" : ""));
        }
        projects.save(fresh);
    }

    /**
     * Lượt "chỉ chạy clip đã chọn" đã xong: bỏ đánh dấu, dự án về DONE nếu không còn clip chờ, nếu không thì PAUSED
     * (các clip khác vẫn chờ – bấm Chạy video để chạy hết).
     */
    private void finishSelected(ScriptProject fresh) {
        List<ScriptClip> picked = clips.findByProjectIdOrderBySeq(fresh.getId()).stream().filter(ScriptClip::isRunSelected).toList();
        long ok = picked.stream().filter(c -> c.getStatus() == ScriptClip.Status.SUCCEEDED).count();
        long failed = picked.stream().filter(c -> c.getStatus() == ScriptClip.Status.FAILED).count();
        long left = clips.countByProjectIdAndStatus(fresh.getId(), ScriptClip.Status.PENDING);
        clips.clearRunSelected(fresh.getId());
        fresh.setOnlySelected(false);
        String done = "Đã chạy xong " + picked.size() + " clip đã chọn: " + ok + " thành công" + (failed > 0 ? ", " + failed + " lỗi" : "");
        if (left > 0) {
            fresh.setStatus(ScriptProject.Status.PAUSED);
            fresh.setPausedReason(done + ". Còn " + left + " clip chưa chạy – bấm “Chạy video” để chạy hết.");
        } else {
            fresh.setStatus(ScriptProject.Status.DONE);
            fresh.setPausedReason(done);
        }
        projects.save(fresh);
    }

    /** Mã tham chiếu của clip chưa gán ảnh hoặc ảnh đã bị xoá khỏi máy. */
    public List<String> missingRefs(ScriptClip c, Map<String, ScriptReference> refs) {
        return refCheck(refs).missing(c);
    }

    /** RefCheck theo nguồn video của dự án chứa các mã (BOTSD / 888API gửi ảnh thẳng → không tính chặn Virtual Portrait). */
    public RefCheck refCheck(Map<String, ScriptReference> refs) {
        boolean raw = refs.values().stream().findFirst().map(ScriptReference::getProjectId).flatMap(projects::findById)
                .map(ScriptProject::sendsRawImages).orElse(false);
        return new RefCheck(refs, raw);
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
        /** Nguồn gửi ảnh thẳng (BOTSD / 888API): chỉ cần có file, bỏ qua chặn Virtual Portrait. */
        private final boolean raw;
        private ScriptProject project;
        private boolean projectLoaded;

        private ScriptProject project() {
            if (!projectLoaded) {
                projectLoaded = true;
                project = refs.values().stream().findFirst().map(ScriptReference::getProjectId).flatMap(projects::findById).orElse(null);
            }
            return project;
        }

        /** Mã trỏ tới file của dự án khác (dữ liệu lạ – luồng bình thường đã chép ảnh thành file riêng của dự án). */
        public boolean foreign(String code) {
            ScriptReference r = refs.get(code);
            if (r == null || r.getFileId() == null) return false;
            GeneratedFile f = files.get(r.getFileId());
            return f != null && f.getProjectId() != null && !f.getProjectId().equals(r.getProjectId());
        }

        RefCheck(Map<String, ScriptReference> refs, boolean raw) {
            this.refs = refs;
            this.raw = raw;
            List<Long> ids = refs.values().stream().map(ScriptReference::getFileId).filter(java.util.Objects::nonNull).distinct().toList();
            this.files = ids.isEmpty() ? Map.of() : fileLibrary.existing(ids).stream()
                    .collect(Collectors.toMap(GeneratedFile::getId, f -> f, (a, b) -> a));
        }

        public List<String> missing(ScriptClip c) {
            if (raw) return missingFiles(c);
            List<String> out = new ArrayList<>();
            for (String code : c.refList()) if (unusable(code)) out.add(code);
            return out;
        }

        /** Mã chưa có ảnh dùng được (bỏ qua chặn Virtual Portrait – dùng cho BOTSD, ảnh gửi thẳng base64). */
        public List<String> missingFiles(ScriptClip c) {
            List<String> out = new ArrayList<>();
            for (String code : c.refList()) if (noFile(code)) out.add(code);
            return out;
        }

        /** Chưa gán ảnh hoặc ảnh không còn dùng được. */
        public boolean noFile(String code) {
            return noFile.computeIfAbsent(code, k -> {
                ScriptReference r = refs.get(k);
                // Đang tạo / chờ duyệt ảnh mới → chưa dùng ảnh cũ
                return r == null || r.getFileId() == null || r.isAwaitingImage() || !fileUsable(files.get(r.getFileId())) || foreign(k);
            });
        }

        /** Thiếu ảnh hoặc ảnh đang bị chặn. */
        public boolean unusable(String code) {
            return unusable.computeIfAbsent(code, k -> noFile(k) || isBlocked(refs.get(k), files.get(refs.get(k).getFileId()), project()));
        }

        /** Như {@link ScriptRunner#isBlocked(ScriptReference)} nhưng dùng file đã tải sẵn. */
        public boolean blocked(ScriptReference r) {
            if (raw) return false;
            return isBlocked(r, r.getFileId() == null ? null : files.get(r.getFileId()), project());
        }
    }

    /** Ảnh bị chặn và chưa được đẩy Virtual Portrait (asset Active thì gửi asset:// → không còn bị chặn). */
    public boolean isBlocked(ScriptReference r) {
        if (r.getBlockedReason() == null) return false;
        return isBlocked(r, r.getFileId() == null ? null : fileLibrary.existing(List.of(r.getFileId())).stream().findFirst().orElse(null),
                projects.findById(r.getProjectId()).orElse(null));
    }

    private boolean isBlocked(ScriptReference r, GeneratedFile f, ScriptProject project) {
        if (r.getBlockedReason() == null) return false;
        try {
            if (f == null) return true;
            if (f.getAssetId() != null && "Active".equals(f.getAssetStatus())) return false;
            if (assetLibrary.cachedAssetFor(r.getCode(), project) != null) return false; // có asset VP trùng tên → gắn khi gửi clip
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
            // Task đã gửi thì theo dõi ở đúng nơi đã gửi; clip mới gửi theo nguồn của dự án
            if (clip.getTaskId() != null ? clip.getTaskId().startsWith(BOTSD_PREFIX) : project.isBotsd()) {
                runBotsd(clip, project, say);
                return;
            }
            if (clip.getTaskId() != null ? clip.getTaskId().startsWith(API888_PREFIX) : project.isApi888()) {
                runApi888(clip, project, say);
                return;
            }
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
            GeneratedFile f = fileLibrary.recordFor(project.getId(), new SavedOutput(target, url), model.id(), clip.getPrompt(), taskId);
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

    // ---------- BOTSD ----------

    private static final long BOTSD_POLL_MS = 15_000;
    private static final long MAX_IMAGE_BYTES = 30L * 1024 * 1024;

    private void runBotsd(ScriptClip clip, ScriptProject project, Consumer<String> say) throws Exception {
        Long clipId = clip.getId();
        BotsdService.BotsdModel m = BotsdService.model(project.getBotsdModel());
        String jobId;
        if (clip.getTaskId() == null) {
            Map<String, Object> payload = botsdPayload(clip, project, m);
            say.accept("Đang gửi task lên BOTSD (" + m.name() + ", " + payload.get("seconds") + "s)…");
            jobId = submitBotsd(payload, say);
            String t = BOTSD_PREFIX + jobId;
            updateClip(clipId, c -> c.setTaskId(t));
        } else {
            jobId = clip.getTaskId().substring(BOTSD_PREFIX.length());
            say.accept("Theo dõi tiếp task BOTSD " + jobId);
        }

        long nextPoll = 0;
        int netErrors = 0;
        while (true) {
            if (cancelRequested.contains(clipId)) {
                finish(clipId, ScriptClip.Status.CANCELLED,
                        "Đã dừng theo dõi – BOTSD không có lệnh huỷ nên task " + jobId + " vẫn chạy trên BOTSD (Point đã trừ)", true);
                return;
            }
            if (System.currentTimeMillis() >= nextPoll) {
                Map<String, Object> r;
                try {
                    r = botsd.job(jobId);
                    netErrors = 0;
                } catch (ark.integration.botsd.BotsdException e) {
                    if (e.status() != 429 && e.status() < 500) throw e;
                    int wait = e.retryAfterSeconds() != null ? e.retryAfterSeconds() : 30;
                    say.accept("BOTSD bận (" + e.status() + "), xem lại tiến độ sau " + wait + "s");
                    nextPoll = System.currentTimeMillis() + wait * 1000L;
                    continue;
                } catch (IOException e) {
                    // Lỗi mạng khi xem tiến độ: không tạo task mới, chỉ xem lại đúng job này
                    if (++netErrors > 20) throw e;
                    say.accept("Lỗi mạng khi xem tiến độ (" + netErrors + "), thử lại sau 20s…");
                    nextPoll = System.currentTimeMillis() + 20_000;
                    continue;
                }
                Object job = Json.path(r, "job");
                String message = Json.str(job, "message");
                if (Boolean.TRUE.equals(Json.path(job, "failed"))) {
                    throw new TaskFailed("BotsdFailed", "BOTSD báo lỗi: " + (message == null ? Json.str(job, "status") : message));
                }
                if (Boolean.TRUE.equals(Json.path(job, "videoReady"))) break;
                Object pct = Json.path(job, "progressPercent");
                say.accept("BOTSD: " + (message != null ? message : Json.str(job, "status")) + (pct != null ? " (" + pct + "%)" : ""));
                nextPoll = System.currentTimeMillis() + BOTSD_POLL_MS;
            }
            Thread.sleep(1000);
        }

        Path target = freeTarget(project, clip.getCode(), "mp4");
        say.accept("Đang tải video từ BOTSD…");
        botsd.download(jobId, target);
        GeneratedFile f = fileLibrary.recordFor(project.getId(), new SavedOutput(target, null), "botsd-" + m.id(), clip.getPrompt(),
                BOTSD_PREFIX + jobId);
        updateClip(clipId, c -> {
            c.setStatus(ScriptClip.Status.SUCCEEDED);
            c.setFileId(f.getId());
            c.setError(null);
            c.setFinishedAt(Instant.now());
        });
    }

    /** Payload /api/web/create (chưa có clientToken): ảnh tham chiếu theo đúng thứ tự clip, base64 không tiền tố data:. */
    private Map<String, Object> botsdPayload(ScriptClip clip, ScriptProject project, BotsdService.BotsdModel m) throws IOException {
        String prompt = clip.getPrompt();
        int maxLen = m.id().startsWith("sd25") ? 15_000 : 20_000;
        if (prompt.length() > maxLen) {
            throw new IllegalArgumentException("Prompt " + prompt.length() + " ký tự > tối đa " + maxLen + " của " + m.name() + " trên BOTSD");
        }
        List<String> codes = clip.refList();
        if (codes.size() > m.maxImages()) {
            throw new IllegalArgumentException(m.name() + " chỉ nhận tối đa " + m.maxImages() + " ảnh, clip có " + codes.size()
                    + " ảnh – đổi model BOTSD trong Cấu hình chạy");
        }
        Map<String, ScriptReference> refs = references.findByProjectIdOrderByKindAscCodeAsc(project.getId()).stream()
                .collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
        List<Map<String, Object>> images = new ArrayList<>();
        for (String code : codes) {
            ScriptReference r = refs.get(code);
            if (r == null || r.getFileId() == null) throw new IllegalArgumentException("Chưa gán ảnh cho mã " + code);
            GeneratedFile f = fileLibrary.get(r.getFileId());
            Path path = fileLibrary.pathOf(f);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Ảnh của mã " + code + " (" + f.getName() + ") không còn trên máy – BOTSD cần file ảnh");
            }
            String name = path.getFileName().toString();
            if (Files.size(path) > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Ảnh " + name + " của mã " + code + " > 30 MB");
            byte[] bytes = Files.readAllBytes(path);
            String mime = imageMime(bytes, name);
            if (mime == null) throw new IllegalArgumentException("Ảnh " + name + " của mã " + code + ": BOTSD chỉ nhận jpg / png / webp");
            // Tên file khớp định dạng thật (vd ảnh PNG đặt đuôi .jpg)
            String ext = switch (mime) {
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                default -> ".jpg";
            };
            Map<String, Object> img = new java.util.LinkedHashMap<>();
            img.put("name", name.replaceFirst("\\.[^.]+$", "") + ext);
            img.put("type", mime);
            img.put("durationMs", 0);
            img.put("data", java.util.Base64.getEncoder().encodeToString(bytes));
            img.put("clientId", code);
            images.add(img);
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("model", m.id());
        body.put("seconds", BotsdService.seconds(m, clip.getDuration()));
        body.put("ratio", project.getRatio());
        body.put("resolution", project.getBotsdResolution());
        body.put("images", images);
        String rp = project.getBotsdRealPerson();
        if (!"OFF".equals(rp)) {
            if (images.isEmpty()) throw new IllegalArgumentException("Chế độ người thật của BOTSD cần ít nhất một ảnh tham chiếu");
            body.put("DEEP".equals(rp) ? "realPersonDeepMode" : "realPersonMode", true);
        }
        return body;
    }

    /** Định dạng ảnh theo nội dung (chữ ký đầu file), không có thì theo đuôi file. */
    static String imageMime(byte[] b, String name) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                && b[11] == 'P') return "image/webp";
        return imageMime(name);
    }

    static String imageMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return null;
    }

    /**
     * Gửi task BOTSD (lần lượt từng task). Lỗi mạng / hết giờ chờ phản hồi thì chưa chắc BOTSD đã nhận: so lịch sử trước và
     * sau – có đúng một task mới thì nhận task đó; không có thì gửi lại an toàn (lỗi tạm thời); không xác định được thì
     * tạm dừng dự án để người dùng kiểm tra, không tự gửi lại (tránh trừ Point 2 lần).
     */
    private String submitBotsd(Map<String, Object> payload, Consumer<String> say) throws Exception {
        synchronized (botsdCreateLock) {
            Set<String> before = null;
            try {
                before = botsdJobIds(botsd.history());
            } catch (Exception e) {
                log.debug("Không đọc được lịch sử BOTSD trước khi gửi: {}", GlobalExceptionHandler.describe(e));
            }
            try {
                Map<String, Object> r = botsd.create(payload);
                String id = Json.str(r, "job", "id");
                if (id == null || id.isBlank()) throw new IllegalStateException("BOTSD không trả về job.id – kiểm tra lịch sử BOTSD rồi bấm Chạy");
                return id;
            } catch (ark.integration.botsd.BotsdException e) {
                throw e; // BOTSD đã trả lời rõ ràng (task không được tạo)
            } catch (IOException e) {
                say.accept("Mất kết nối khi gửi – kiểm tra lịch sử BOTSD xem task đã được nhận chưa…");
                Thread.sleep(5000);
                Set<String> after;
                try {
                    after = botsdJobIds(botsd.history());
                } catch (Exception e2) {
                    after = null;
                }
                if (before != null && after != null) {
                    after.removeAll(before);
                    if (after.size() == 1) {
                        String id = after.iterator().next();
                        log.info("BOTSD đã nhận task {} dù mất kết nối khi gửi", id);
                        return id;
                    }
                    if (after.isEmpty()) throw e; // chưa nhận → thử lại như lỗi mạng thường
                }
                throw new IllegalStateException("Không chắc BOTSD đã nhận clip (mất kết nối khi gửi: "
                        + GlobalExceptionHandler.describe(e) + ") – xem lịch sử trên botsd.pro, tránh trừ Point 2 lần, rồi bấm Chạy");
            }
        }
    }

    private static Set<String> botsdJobIds(List<?> jobs) {
        Set<String> ids = new java.util.HashSet<>();
        for (Object j : jobs) {
            String id = Json.str(j, "id");
            if (id != null) ids.add(id);
        }
        return ids;
    }

    // ---------- 888API ----------

    private static final long API888_POLL_MS = 8_000;

    /** 888API không đọc được link ảnh tham chiếu dù đã đổi link mới vài lần (task chưa tạo, không tốn tiền). */
    static final class Api888RefUnreadable extends IllegalStateException {
        Api888RefUnreadable(String m) {
            super(m);
        }
    }

    /** Số lần đổi link mới cho ảnh 888API không đọc được trước khi báo lỗi clip. */
    static final int API888_REF_RELINKS = 2;

    /** Lỗi "không đọc được ảnh tham chiếu" (REFERENCE_UNREADABLE / reference_prepare_failed): task chưa được tạo. */
    static boolean refUnreadable(ark.integration.api888.Api888Exception e) {
        String s = (e.code() + " " + e.getMessage()).toUpperCase(Locale.ROOT);
        return s.contains("REFERENCE_UNREADABLE") || s.contains("REFERENCE_PREPARE_FAILED");
    }

    /** Ảnh thứ mấy bị lỗi (1-based), theo "第 4 份参考图片" / "image 4" / "reference 4"; null nếu không rõ. */
    static Integer refIndex(String msg) {
        if (msg == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*(?:份|张|个)").matcher(msg);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("(?i)(?:image|reference|ref)\\s*#?\\s*(\\d+)").matcher(msg);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Lỗi khi gửi task 888API mà không chắc máy chủ đã nhận (không tự gửi lại – tránh trừ tiền 2 lần). */
    static final class Api888Uncertain extends IllegalStateException {
        Api888Uncertain(String m) {
            super(m);
        }
    }

    private void runApi888(ScriptClip clip, ScriptProject project, Consumer<String> say) throws Exception {
        Long clipId = clip.getId();
        Api888Service.Model m = Api888Service.model(project.getApi888Model());
        ark.integration.api888.Api888Client client = api888.client();
        String taskId;
        if (clip.getTaskId() == null) {
            taskId = submitApi888Relinking(client, clip, project, m, say);
            String t = API888_PREFIX + taskId;
            updateClip(clipId, c -> c.setTaskId(t));
        } else {
            taskId = clip.getTaskId().substring(API888_PREFIX.length());
            say.accept("Theo dõi tiếp task 888API " + taskId);
        }

        long nextPoll = 0;
        int netErrors = 0;
        int notFound = 0;
        String url = null;
        while (true) {
            if (cancelRequested.contains(clipId)) {
                finish(clipId, ScriptClip.Status.CANCELLED,
                        "Đã dừng theo dõi – 888API không có lệnh huỷ nên task " + taskId + " vẫn chạy trên 888API", true);
                return;
            }
            if (System.currentTimeMillis() >= nextPoll) {
                Map<String, Object> t;
                try {
                    t = client.task(taskId);
                    netErrors = 0;
                } catch (ark.integration.api888.Api888Exception e) {
                    // Tài liệu: một lần 404 chưa chắc task mất → xem lại vài lần
                    if ((e.status() == 404 || e.status() == 410) && ++notFound < 5) {
                        say.accept("888API chưa thấy task (" + e.status() + "), xem lại sau 30s…");
                        nextPoll = System.currentTimeMillis() + 30_000;
                        continue;
                    }
                    if (e.status() != 429 && e.status() < 500) throw e;
                    int wait = e.retryAfterSeconds() != null ? Math.max(5, e.retryAfterSeconds()) : 30;
                    say.accept("888API bận (" + e.status() + "), xem lại tiến độ sau " + wait + "s");
                    nextPoll = System.currentTimeMillis() + wait * 1000L;
                    continue;
                } catch (IOException e) {
                    if (++netErrors > 30) throw e;
                    say.accept("Lỗi mạng khi xem tiến độ (" + netErrors + "), thử lại sau 20s…");
                    nextPoll = System.currentTimeMillis() + 20_000;
                    continue;
                }
                Object task = Json.str(t, "status") == null && Json.path(t, "data") instanceof Map ? Json.path(t, "data") : t;
                String status = String.valueOf(Json.str(task, "status")).toLowerCase(Locale.ROOT);
                if (status.equals("failed") || status.equals("failure") || status.equals("error") || status.equals("cancelled")) {
                    throw new TaskFailed("Api888Failed", "888API báo lỗi: " + api888Error(task));
                }
                if (status.equals("completed") || status.equals("succeeded") || status.equals("success")) {
                    url = Json.str(task, "url");
                    if (url == null) url = Json.str(task, "video_url");
                    if (url == null) url = Json.str(task, "content", "video_url");
                    break;
                }
                Object pct = Json.path(task, "progress");
                say.accept("888API: " + (status.equals("queued") ? "đang xếp hàng" : status.equals("in_progress") ? "đang tạo" : status)
                        + (pct != null ? " (" + pct + "%)" : ""));
                nextPoll = System.currentTimeMillis() + API888_POLL_MS;
            }
            Thread.sleep(1000);
        }

        Path target = freeTarget(project, clip.getCode(), "mp4");
        say.accept("Đang tải video từ 888API…");
        try {
            client.download(taskId, url, target);
        } catch (ark.integration.api888.Api888Exception e) {
            if (url == null || e.status() < 400 || e.status() >= 500) throw e;
            client.download(taskId, null, target); // link hết hạn → lấy qua /v1/videos/{id}/content
        }
        GeneratedFile f = fileLibrary.recordFor(project.getId(), new SavedOutput(target, null), "888-" + m.id(), clip.getPrompt(),
                API888_PREFIX + taskId);
        updateClip(clipId, c -> {
            c.setStatus(ScriptClip.Status.SUCCEEDED);
            c.setFileId(f.getId());
            c.setError(null);
            c.setFinishedAt(Instant.now());
        });
    }

    static String api888Error(Object task) {
        Object err = Json.path(task, "error");
        String msg = err instanceof Map ? Json.str(err, "message") : err instanceof String s ? s : null;
        String code = err instanceof Map ? Json.str(err, "code") : null;
        if (msg == null) msg = Json.str(task, "message");
        if (msg == null) msg = Json.str(task, "fail_reason");
        if (msg == null) msg = "task failed";
        return code != null && !msg.contains(code) ? code + " – " + msg : msg;
    }

    /**
     * Gửi task; 888API không đọc được link ảnh (REFERENCE_UNREADABLE – chưa tạo task, không tốn tiền) → bỏ link cũ, tải ảnh
     * đó lên lại lấy link mới, chờ rồi gửi lại (tài liệu: không thử lại liên tục cùng một link). Hết lượt → lỗi rõ ảnh nào.
     */
    private String submitApi888Relinking(ark.integration.api888.Api888Client client, ScriptClip clip, ScriptProject project,
                                         Api888Service.Model m, Consumer<String> say) throws Exception {
        List<String> codes = clip.refList();
        for (int round = 0; ; round++) {
            Map<String, Object> body = api888Payload(clip, project, m, say);
            say.accept("Đang gửi task lên 888API (" + m.id() + ", " + body.get("duration") + "s)…");
            try {
                return submitApi888(client, body);
            } catch (ark.integration.api888.Api888Exception e) {
                if (!refUnreadable(e)) throw e;
                Integer idx = refIndex(e.getMessage());
                String which = idx != null && idx >= 1 && idx <= codes.size() ? "ảnh " + idx + " (mã " + codes.get(idx - 1) + ")" : "ảnh tham chiếu";
                if (round >= API888_REF_RELINKS) {
                    String host = "LITTERBOX".equals(api888.imageHost())
                            ? "Litterbox đang chậm / bị chặn với máy chủ 888API – nên bật TOS (console.byteplus.com → Object Storage) rồi chọn “Nơi để ảnh: TOS” ở Cài đặt › 888API"
                            : "kiểm tra ảnh còn mở được không, giảm số luồng song song, hoặc thử “Nơi để ảnh: Litterbox” ở Cài đặt › 888API";
                    throw new Api888RefUnreadable("888API không đọc được " + which + " dù đã đổi link mới " + API888_REF_RELINKS
                            + " lần (chưa tạo task, không tốn tiền) – " + host + ", rồi bấm Chạy lại. Chi tiết: " + e.getMessage());
                }
                // bỏ link cũ: ảnh được chỉ rõ, không rõ thì cả các ảnh của clip
                Map<String, ScriptReference> refs = references.findByProjectIdOrderByKindAscCodeAsc(project.getId()).stream()
                        .collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
                List<String> stale = idx != null && idx >= 1 && idx <= codes.size() ? List.of(codes.get(idx - 1)) : codes;
                for (String c : stale) {
                    ScriptReference r = refs.get(c);
                    if (r != null && r.getFileId() != null) api888.forgetUrl(r.getFileId());
                }
                int wait = 15 * (round + 1);
                say.accept("888API không đọc được " + which + " – tải ảnh lên lại lấy link mới, gửi lại sau " + wait + "s (lần " + (round + 1)
                        + "/" + API888_REF_RELINKS + ")");
                Thread.sleep(wait * 1000L);
            }
        }
    }

    /** Body POST /v1/videos: ảnh tham chiếu theo đúng thứ tự clip (Ảnh 1, 2…), là link HTTPS của ảnh trong dự án. */
    private Map<String, Object> api888Payload(ScriptClip clip, ScriptProject project, Api888Service.Model m, Consumer<String> say)
            throws Exception {
        String prompt = clip.getPrompt();
        if (m.promptMax() > 0 && prompt.length() > m.promptMax()) {
            throw new IllegalArgumentException("Prompt " + prompt.length() + " ký tự > tối đa " + m.promptMax() + " của " + m.id()
                    + " trên 888API – đổi model trong Cấu hình chạy");
        }
        List<String> codes = clip.refList();
        if (codes.size() > m.maxImages()) {
            throw new IllegalArgumentException(m.id() + " chỉ nhận tối đa " + m.maxImages() + " ảnh, clip có " + codes.size()
                    + " ảnh – đổi model 888API trong Cấu hình chạy");
        }
        Map<String, ScriptReference> refs = references.findByProjectIdOrderByKindAscCodeAsc(project.getId()).stream()
                .collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
        List<String> images = new ArrayList<>();
        for (String code : codes) {
            ScriptReference r = refs.get(code);
            if (r == null || r.getFileId() == null) throw new IllegalArgumentException("Chưa gán ảnh cho mã " + code);
            GeneratedFile f = fileLibrary.get(r.getFileId());
            Path path = fileLibrary.pathOf(f);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Ảnh của mã " + code + " (" + f.getName() + ") không còn trên máy");
            }
            if (Files.size(path) > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Ảnh " + f.getName() + " của mã " + code + " > 30 MB");
            if (m.id().endsWith("-face")) {
                String mime = imageMime(Files.readAllBytes(path), f.getName());
                if (!"image/png".equals(mime) && !"image/jpeg".equals(mime)) {
                    throw new IllegalArgumentException(m.id() + " chỉ nhận ảnh PNG/JPEG – ảnh " + f.getName() + " của mã " + code + " không phải");
                }
            }
            try {
                images.add(api888.imageUrl(f, project.getFolder(), msg -> say.accept(code + ": " + msg)));
            } catch (IllegalStateException e) {
                String why = e.getMessage() == null ? "" : e.getMessage();
                if (why.contains("TOS") && !why.contains("Litterbox")) {
                    why += " – hoặc đổi “Nơi để ảnh” sang Litterbox ở Cài đặt › 888API (link công khai, tự xoá sau 72 giờ)";
                }
                throw new IllegalStateException("Không tạo được link ảnh " + code + " cho 888API: " + why, e);
            }
        }
        int seconds = Api888Service.seconds(m, clip.getDuration());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", m.id());
        body.put("prompt", prompt);
        body.put("duration", seconds);
        body.put("seconds", seconds); // có model bắt buộc seconds; gửi cả hai cùng giá trị là hợp lệ
        body.put("ratio", project.getRatio());
        body.put("resolution", m.resolution());
        if (!images.isEmpty()) body.put(m.imageField(), images);
        return body;
    }

    /**
     * Gửi task 888API. Tài liệu: không tự gửi lại khi hết giờ / mất kết nối sau khi đã gửi (task có thể đã được tạo).
     * Không kết nối được (chưa gửi) → lỗi mạng thường, thử lại an toàn; còn lại → tạm dừng dự án cho người dùng kiểm tra.
     */
    private String submitApi888(ark.integration.api888.Api888Client client, Map<String, Object> body) throws Exception {
        Map<String, Object> r;
        try {
            r = client.create(body);
        } catch (ark.integration.api888.Api888Exception e) {
            if (e.status() == 500 || e.status() == 502 || e.status() == 504) {
                throw new Api888Uncertain("Không chắc 888API đã nhận clip (HTTP " + e.status() + " khi gửi: " + e.getMessage()
                        + ") – xem Nhật ký trên 888api.xin/console, tránh trả tiền 2 lần, rồi bấm Chạy");
            }
            throw e;
        } catch (java.net.ConnectException | java.nio.channels.UnresolvedAddressException e) {
            throw new IOException("Không kết nối được 888API: " + GlobalExceptionHandler.describe(e), e);
        } catch (IOException e) {
            throw new Api888Uncertain("Không chắc 888API đã nhận clip (mất kết nối khi gửi: " + GlobalExceptionHandler.describe(e)
                    + ") – xem Nhật ký trên 888api.xin/console, tránh trả tiền 2 lần, rồi bấm Chạy");
        }
        String id = ark.integration.api888.Api888Client.taskId(r);
        if (id == null) throw new Api888Uncertain("888API không trả về mã task – xem Nhật ký trên 888api.xin/console rồi bấm Chạy");
        return id;
    }

    /**
     * Có asset Virtual Portrait Active tên trùng mã (tên asset hoặc tên group, vd upload trên console) → tải về thư viện,
     * gán cho mã, bỏ đánh dấu chặn. Trả về file đã gắn asset, hoặc null nếu không có / lỗi.
     */
    private GeneratedFile linkVpAsset(ScriptProject project, ScriptReference r, Consumer<String> say) {
        if (!assetLibrary.hasKeys()) return null;
        try {
            Map<String, Object> hit = assetLibrary.findAssetFor(assetLibrary.activeAssetsCached(false), r.getCode(), project);
            if (hit == null) return null;
            GeneratedFile f;
            synchronized (this) {
                f = assetLibrary.fileForAsset(hit, fileLibrary.root().resolve("assets").resolve(project.getFolder()), r.getCode(), project.getId());
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
                        ok = assetLibrary.ensureActiveAsset(f, project, m -> say.accept(code + ": " + m), () -> cancelRequested.contains(clipId));
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
        // Dự án theo thư mục: lưu thẳng vào <dự án>/Videos (tạo nếu chưa có)
        Path dir = project.isFolderProject() ? ProjectFolderService.videoDirOf(project)
                : fileLibrary.root().resolve("videos").resolve(project.getFolder());
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
        if (e instanceof Api888RefUnreadable) {
            finish(clipId, ScriptClip.Status.FAILED, msg, true);
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
        if (e instanceof ark.integration.botsd.BotsdException be) {
            int s = be.status();
            if (s == 429) {
                int wait = be.retryAfterSeconds() != null ? Math.max(5, be.retryAfterSeconds()) : (int) COOLDOWN_SECONDS;
                requeue(clipId, "BOTSD: gửi quá nhanh (429) – chờ " + wait + "s rồi gửi lại");
                if (project != null) updateProject(project.getId(), p -> p.setCooldownUntil(Instant.now().plusSeconds(wait)));
            } else if (s == 409) {
                requeue(clipId, "BOTSD: đã đạt giới hạn task đang chạy – chờ task trước xong rồi gửi tiếp");
                if (project != null) updateProject(project.getId(), p -> p.setCooldownUntil(Instant.now().plusSeconds(30)));
            } else if (s == 401) {
                requeue(clipId, msg);
                pause(project, "Đăng nhập BOTSD hết hạn / sai mật khẩu – vào Cài đặt › BOTSD đăng nhập lại rồi bấm Chạy");
            } else if (s == 402) {
                requeue(clipId, msg);
                pause(project, "Không đủ Point BOTSD – nạp thêm Point (hoặc chọn model / thời lượng rẻ hơn) rồi bấm Chạy");
            } else if (s == 404 || s == 410) {
                transient_(clipId, project, msg, true); // task / dữ liệu không còn → gửi task mới
            } else if (s >= 500) {
                transient_(clipId, project, msg, false);
            } else {
                finish(clipId, ScriptClip.Status.FAILED, msg, true);
            }
            return;
        }
        if (e instanceof ark.integration.api888.Api888Exception ae) {
            int s = ae.status();
            String code = ae.code() == null ? "" : ae.code().toUpperCase(Locale.ROOT);
            if (s == 429) {
                int wait = ae.retryAfterSeconds() != null ? Math.max(5, ae.retryAfterSeconds()) : (int) COOLDOWN_SECONDS;
                requeue(clipId, "888API: gửi quá nhanh (429) – chờ " + wait + "s rồi gửi lại");
                if (project != null) updateProject(project.getId(), p -> p.setCooldownUntil(Instant.now().plusSeconds(wait)));
            } else if (ae.quota()) {
                requeue(clipId, msg);
                pause(project, "888API: không đủ số dư / hạn mức key – nạp thêm (hoặc chọn model rẻ hơn) rồi bấm Chạy");
            } else if (s == 401 || s == 403) {
                requeue(clipId, msg);
                pause(project, "888API từ chối key (" + s + "): " + ae.getMessage() + " – kiểm tra key trong Cài đặt › 888API và quyền model "
                        + (project == null ? "" : project.getApi888Model()) + " trên console, rồi bấm Chạy");
            } else if (code.contains("RELAY_BUSY") || code.contains("RELAY_UNAVAILABLE") || code.contains("UNREACHABLE")) {
                transient_(clipId, project, msg, true); // 888API bận đọc ảnh – task chưa tạo, gửi lại sau
            } else if (s == 404 || s == 410) {
                transient_(clipId, project, msg, true); // task không còn → gửi task mới
            } else if (s >= 500) {
                transient_(clipId, project, msg, false);
            } else {
                finish(clipId, ScriptClip.Status.FAILED, "888API: " + ae.getMessage(), true);
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
