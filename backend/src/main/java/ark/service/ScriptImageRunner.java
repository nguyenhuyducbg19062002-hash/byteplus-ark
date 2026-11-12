package ark.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import ark.entity.ScriptProject;
import ark.entity.ScriptReference;
import ark.entity.ScriptReference.ImageStatus;
import ark.exception.GlobalExceptionHandler;
import ark.integration.byteplus.ArkClient;
import ark.integration.byteplus.ArkException;
import ark.integration.byteplus.ImageGenerator;
import ark.integration.byteplus.Models;
import ark.integration.byteplus.SavedOutput;
import ark.repository.ScriptProjectRepository;
import ark.repository.ScriptReferenceRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hàng đợi tạo ảnh tham chiếu bằng Seedream cho các mã đang PENDING (chạy độc lập với trạng thái video của dự án).
 * <ul>
 * <li>Nhân vật nhiều bộ trang phục: ảnh bộ đầu (identityCode) được tạo trước và dùng làm tham chiếu khuôn mặt cho các bộ sau.</li>
 * <li>Ảnh xong được lưu thành output/assets/&lt;dự án&gt;/&lt;mã&gt;.jpeg, gán ngay cho mã, và tự đẩy Virtual Portrait nếu bật.</li>
 * <li>429: chờ 60s; 5xx / mạng: thử lại tới maxAttempts; nội dung bị từ chối / sai tham số: FAILED.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptImageRunner {
    public static final String DEFAULT_IMAGE_MODEL = "seedream-5-0-260128";
    private static final long COOLDOWN_SECONDS = 60;

    private final Config config;
    private final ScriptProjectRepository projects;
    private final ScriptReferenceRepository references;
    private final FileLibraryService fileLibrary;
    private final AssetLibraryService assetLibrary;
    private final ScriptRunner videoRunner;

    private final ExecutorService pool = Executors.newCachedThreadPool(); // giới hạn bằng "song song" của dự án
    /** refId → projectId đang tạo trong tiến trình này. */
    private final Map<Long, Long> active = new ConcurrentHashMap<>();
    private final Map<Long, String> lastLog = new ConcurrentHashMap<>();
    private final Map<Long, Instant> cooldown = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int n = references.updateImageStatus(ImageStatus.GENERATING, ImageStatus.PENDING);
        if (n > 0) log.info("Requeued {} reference image(s) after restart", n);
    }

    public String lastLog(Long refId) {
        return lastLog.get(refId);
    }

    public static Models.ImageModel model(ScriptProject p) {
        return Models.image(p.getImageModel() == null ? DEFAULT_IMAGE_MODEL : p.getImageModel());
    }

    @Scheduled(fixedDelay = 1500, initialDelay = 6000)
    public synchronized void dispatch() {
        Map<Long, List<ScriptReference>> pending = references.findByImageStatus(ImageStatus.PENDING).stream()
                .collect(Collectors.groupingBy(ScriptReference::getProjectId));
        for (Map.Entry<Long, List<ScriptReference>> e : pending.entrySet()) {
            try {
                dispatch(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.error("Image dispatch failed for project {}", e.getKey(), ex);
            }
        }
    }

    private void dispatch(Long projectId, List<ScriptReference> queue) {
        Instant cd = cooldown.get(projectId);
        if (cd != null && cd.isAfter(Instant.now())) return;
        ScriptProject p = projects.findById(projectId).orElse(null);
        if (p == null) return;
        Map<String, ScriptReference> all = references.findByProjectIdOrderByKindAscCodeAsc(projectId).stream()
                .collect(Collectors.toMap(ScriptReference::getCode, r -> r, (a, b) -> a));
        long running = active.values().stream().filter(projectId::equals).count();
        // Ảnh gốc trước, rồi theo loại / mã
        List<ScriptReference> ordered = new ArrayList<>(queue);
        ordered.sort((a, b) -> Boolean.compare(a.getIdentityCode() != null, b.getIdentityCode() != null));
        for (ScriptReference r : ordered) {
            if (running >= p.getMaxParallel()) return;
            if (active.containsKey(r.getId())) continue;
            if (r.getIdentityCode() != null) {
                ScriptReference base = all.get(r.getIdentityCode());
                boolean baseBusy = base != null && (base.getImageStatus() == ImageStatus.PENDING || base.getImageStatus() == ImageStatus.GENERATING);
                if (baseBusy) continue; // chờ ảnh gốc để giữ khuôn mặt
            }
            active.put(r.getId(), projectId);
            if (references.moveImageIf(r.getId(), ImageStatus.PENDING, ImageStatus.GENERATING) == 0) {
                active.remove(r.getId());
                continue;
            }
            lastLog.put(r.getId(), "Đang chuẩn bị…");
            running++;
            Long id = r.getId();
            pool.submit(() -> run(id));
        }
    }

    private void run(Long refId) {
        Consumer<String> say = m -> lastLog.put(refId, m);
        ScriptProject project = null;
        try {
            ScriptReference r = references.findById(refId).orElseThrow();
            project = projects.findById(r.getProjectId()).orElseThrow();
            if (r.getImagePrompt() == null || r.getImagePrompt().isBlank()) {
                throw new IllegalArgumentException("Chưa có prompt ảnh cho mã " + r.getCode());
            }
            ImageGenerator.Params p = new ImageGenerator.Params();
            p.model = model(project);
            p.prompt = r.getImagePrompt();
            p.size = project.getImageSize() == null ? p.model.defaultSize() : project.getImageSize();
            p.sequential = false;
            p.maxImages = 1;
            p.watermark = false;
            List<String> refs = new ArrayList<>();
            if (r.getIdentityCode() != null) {
                String id = identityImage(r.getProjectId(), r.getIdentityCode());
                if (id != null) refs.add(id);
                else say.accept("Ảnh gốc " + r.getIdentityCode() + " chưa có – tạo không kèm tham chiếu khuôn mặt");
            }
            p.refs = refs;

            Path tmp = Files.createTempDirectory("ark-ref-");
            List<SavedOutput> out;
            try {
                out = ImageGenerator.run(ArkClient.from(config), p, tmp, say);
                SavedOutput first = out.get(0);
                String name = first.file().getFileName().toString();
                String ext = name.substring(name.lastIndexOf('.') + 1);
                Path dir = fileLibrary.root().resolve("assets").resolve(project.getFolder());
                Files.createDirectories(dir);
                Path target = dir.resolve(r.getCode() + "." + ext);
                for (int v = 2; Files.exists(target); v++) target = dir.resolve(r.getCode() + "_v" + v + "." + ext);
                Files.move(first.file(), target, StandardCopyOption.REPLACE_EXISTING);
                GeneratedFile f = fileLibrary.record(new SavedOutput(target, first.sourceUrl()), p.model.id(), p.prompt, null, null);
                update(refId, x -> {
                    x.setFileId(f.getId());
                    x.setImageStatus(ImageStatus.SUCCEEDED);
                    x.setImageError(null);
                    x.setBlockedReason(null);
                });
                say.accept("Đã tạo ảnh " + target.getFileName());
                autoPush(f, refId);
            } finally {
                deleteQuietly(tmp);
            }
        } catch (Exception e) {
            handleError(refId, project, e);
        } finally {
            active.remove(refId);
            lastLog.remove(refId);
        }
    }

    /** Ảnh của mã gốc: URL gốc còn hạn (đầu ra tin cậy của ModelArk) hoặc file trên máy. */
    private String identityImage(Long projectId, String code) {
        ScriptReference base = references.findByProjectIdAndCode(projectId, code).orElse(null);
        if (base == null || base.getFileId() == null || !videoRunner.fileUsable(base.getFileId())) return null;
        GeneratedFile f = fileLibrary.get(base.getFileId());
        if (f.isSourceUrlValid()) return f.getSourceUrl();
        Path path = fileLibrary.pathOf(f);
        return Files.isRegularFile(path) ? path.toString() : null;
    }

    private void autoPush(GeneratedFile f, Long refId) {
        try {
            JobContext ctx = new JobContext("ref-" + refId, "asset-push", "Đẩy ảnh tham chiếu", Instant.now());
            assetLibrary.autoPush(List.of(f), ctx);
        } catch (Exception e) {
            log.warn("Auto push of reference image {} failed: {}", f.getId(), e.getMessage());
        }
    }

    private void handleError(Long refId, ScriptProject project, Exception e) {
        String msg = GlobalExceptionHandler.describe(e);
        log.warn("Reference image {} error: {}", refId, msg);
        int max = project == null ? 3 : project.getMaxAttempts();
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            update(refId, x -> x.setImageStatus(ImageStatus.PENDING));
            return;
        }
        if (e instanceof ArkException ae) {
            String code = ae.code() == null ? "" : ae.code().toLowerCase(Locale.ROOT);
            if (ae.status() == 429 || code.contains("ratelimit") || code.contains("quota") || code.contains("overload")) {
                if (project != null) cooldown.put(project.getId(), Instant.now().plusSeconds(COOLDOWN_SECONDS));
                update(refId, x -> {
                    x.setImageStatus(ImageStatus.PENDING);
                    x.setImageError("BytePlus giới hạn tốc độ (429) – chờ " + COOLDOWN_SECONDS + "s rồi tạo lại");
                });
                return;
            }
            if (code.contains("modelnotopen") || ae.status() == 401 || ae.status() == 403) {
                // Lỗi tài khoản: các mã khác cũng sẽ lỗi y hệt → dừng cả hàng đợi ảnh của dự án
                String why = code.contains("modelnotopen") && project != null
                        ? ScriptRunner.modelNotOpen(model(project).id(), "ảnh") : "Lỗi tài khoản / quyền: " + msg;
                fail(refId, why);
                if (project != null) {
                    for (ScriptReference other : references.findByProjectIdOrderByKindAscCodeAsc(project.getId())) {
                        if (other.getImageStatus() == ImageStatus.PENDING) {
                            update(other.getId(), x -> {
                                x.setImageStatus(ImageStatus.NONE);
                                x.setImageError("Đã dừng hàng đợi: " + why);
                            });
                        }
                    }
                }
                return;
            }
            if (ae.status() >= 500) {
                retryOrFail(refId, max, msg);
                return;
            }
            fail(refId, msg + (code.contains("sensitive") || code.contains("risk") ? " → sửa prompt rồi tạo lại" : ""));
            return;
        }
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            fail(refId, msg);
            return;
        }
        retryOrFail(refId, max, msg);
    }

    private void retryOrFail(Long refId, int max, String msg) {
        update(refId, x -> {
            int n = x.getImageAttempt() + 1;
            x.setImageAttempt(n);
            if (n < max) {
                x.setImageStatus(ImageStatus.PENDING);
                x.setImageError("Lỗi tạm thời (lần " + n + "/" + max + "), sẽ thử lại: " + msg);
            } else {
                x.setImageStatus(ImageStatus.FAILED);
                x.setImageError("Hết " + max + " lần thử: " + msg);
            }
        });
    }

    private void fail(Long refId, String msg) {
        update(refId, x -> {
            x.setImageStatus(ImageStatus.FAILED);
            x.setImageError(msg);
        });
    }

    private synchronized void update(Long id, Consumer<ScriptReference> change) {
        references.findById(id).ifPresent(r -> {
            change.accept(r);
            if (r.getImageError() != null && r.getImageError().length() > 2000) r.setImageError(r.getImageError().substring(0, 2000));
            references.save(r);
        });
    }

    private static void deleteQuietly(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (IOException ignored) {
            // thư mục tạm
        }
    }

    /** Có ảnh đang chờ / đang tạo trong dự án (bộ chạy video không tạm dừng dự án khi còn ảnh đang tạo). */
    public boolean hasPendingImages(Long projectId) {
        return references.countByProjectIdAndImageStatusIn(projectId, Set.of(ImageStatus.PENDING, ImageStatus.GENERATING)) > 0;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
