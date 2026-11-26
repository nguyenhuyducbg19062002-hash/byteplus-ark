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
 * <li>Ảnh xong được lưu vào thư mục chờ duyệt output/assets/&lt;dự án&gt;/_cho-duyet/&lt;mã&gt;_&lt;thời gian&gt;.jpeg (REVIEW),
 * chưa dùng cho clip. Khi duyệt ({@link #approve}): đổi tên thành output/assets/&lt;dự án&gt;/&lt;mã&gt;.jpeg (tự tạo thư mục),
 * ảnh cũ chuyển vào _cu/ và gán cho mã. Không đẩy Virtual Portrait ở đây – bộ chạy video tự đẩy khi gửi clip.
 * Dự án bật "tự duyệt" thì duyệt ngay.</li>
 * <li>429: chờ 60s; 5xx / mạng: thử lại tới maxAttempts; nội dung bị từ chối / sai tham số: FAILED.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptImageRunner {
    public static final String DEFAULT_IMAGE_MODEL = "seedream-5-0-260128";
    private static final long COOLDOWN_SECONDS = 60;
    /** Thư mục con (trong thư mục ảnh tham chiếu của dự án) chứa ảnh AI chờ duyệt / ảnh cũ đã thay. */
    public static final String REVIEW_DIR = "_cho-duyet";
    public static final String ARCHIVE_DIR = "_cu";
    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Config config;
    private final ScriptProjectRepository projects;
    private final ScriptReferenceRepository references;
    private final FileLibraryService fileLibrary;
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

    /** Model ảnh của dự án; model đã bỏ khỏi danh sách (dự án lưu từ bản cũ) → dùng mặc định thay vì lỗi mọi màn hình. */
    public static Models.ImageModel model(ScriptProject p) {
        String id = p.getImageModel() == null ? DEFAULT_IMAGE_MODEL : p.getImageModel();
        for (Models.ImageModel m : Models.IMAGE) if (m.id().equals(id)) return m;
        return Models.image(DEFAULT_IMAGE_MODEL);
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
                boolean baseBusy = base != null && base.isAwaitingImage();
                if (baseBusy) continue; // chờ ảnh gốc được tạo + duyệt để giữ khuôn mặt
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
                Path dir = pendingDir(project);
                Path target = unique(dir, r.getCode() + "_" + java.time.LocalDateTime.now().format(STAMP), ext(first.file()));
                Files.move(first.file(), target, StandardCopyOption.REPLACE_EXISTING);
                GeneratedFile f = fileLibrary.recordFor(project.getId(), new SavedOutput(target, first.sourceUrl()), p.model.id(), p.prompt, null);
                Long oldPending = r.getPendingFileId();
                update(refId, x -> {
                    x.setPendingFileId(f.getId());
                    x.setImageStatus(ImageStatus.REVIEW);
                    x.setImageError(null);
                });
                if (oldPending != null && !oldPending.equals(f.getId())) deleteFileQuietly(oldPending);
                say.accept("Đã tạo ảnh " + target.getFileName() + " – chờ duyệt");
                if (project.isAutoApproveImages()) {
                    // Ảnh đã tạo xong: tự duyệt lỗi (vd không chép được vào thư mục riêng) không được biến mã thành “Tạo ảnh lỗi”
                    try {
                        approve(refId);
                    } catch (Exception ex) {
                        String why = GlobalExceptionHandler.describe(ex);
                        log.warn("Auto-approve reference {} failed: {}", refId, why);
                        update(refId, x -> {
                            if (x.getImageError() == null) x.setImageError("Tự duyệt lỗi – bấm Duyệt lại: " + why);
                        });
                    }
                }
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

    // ---------- duyệt ảnh ----------

    /** Thư mục ảnh tham chiếu của dự án: output/assets/&lt;thư mục dự án&gt;. */
    public Path refDir(ScriptProject p) {
        if (p.isFolderProject()) return ProjectFolderService.refDirOf(p); // dự án theo thư mục: <dự án>/Ảnh tham chiếu
        return fileLibrary.root().resolve("assets").resolve(p.getFolder());
    }

    /** Ảnh AI chờ duyệt: dự án theo thư mục → &lt;dự án&gt;/Ảnh chờ duyệt, còn lại output/assets/&lt;dự án&gt;/_cho-duyet. */
    public Path pendingDir(ScriptProject p) {
        if (p.isFolderProject()) return ProjectFolderService.pendingDirOf(p);
        return refDir(p).resolve(REVIEW_DIR);
    }

    /**
     * Duyệt ảnh chờ duyệt của mã: đổi tên thành &lt;thư mục ảnh tham chiếu&gt;/&lt;MÃ&gt;.&lt;đuôi&gt; (tạo thư mục nếu chưa có),
     * ảnh cũ của mã (và file khác đang trùng tên) chuyển vào _cu/, gán ảnh cho mã. Việc đẩy Virtual Portrait do bộ chạy video làm khi gửi clip.
     */
    public synchronized void approve(Long refId) throws IOException {
        ScriptReference r = references.findById(refId).orElseThrow();
        if (r.getImageStatus() != ImageStatus.REVIEW || r.getPendingFileId() == null) {
            throw new IllegalStateException("Mã " + r.getCode() + " không có ảnh chờ duyệt");
        }
        ScriptProject project = projects.findById(r.getProjectId()).orElseThrow();
        GeneratedFile f = fileLibrary.get(r.getPendingFileId());
        if (!Files.isRegularFile(fileLibrary.pathOf(f))) {
            update(refId, x -> {
                x.setPendingFileId(null);
                x.setImageStatus(ImageStatus.NONE);
                x.setImageError("Ảnh chờ duyệt không còn trên máy – bấm Tạo lại");
            });
            throw new IllegalStateException("Ảnh chờ duyệt của " + r.getCode() + " không còn trên máy – tạo lại ảnh");
        }
        Path dir = refDir(project);
        Files.createDirectories(dir);
        // Ảnh cũ của mã nằm ngay trong thư mục tham chiếu → cất vào _cu/
        if (r.getFileId() != null && !r.getFileId().equals(f.getId())) {
            GeneratedFile old = fileLibrary.existing(List.of(r.getFileId())).stream().findFirst().orElse(null);
            if (old != null && Files.isRegularFile(fileLibrary.pathOf(old))
                    && dir.toAbsolutePath().normalize().equals(fileLibrary.pathOf(old).getParent())) {
                archive(old, dir, r.getCode());
            }
        }
        Path target = dir.resolve(r.getCode() + "." + ext(fileLibrary.pathOf(f)));
        if (Files.exists(target)) { // file khác đang chiếm tên MÃ.đuôi
            GeneratedFile holder = fileLibrary.findByPath(target).orElse(null);
            if (holder != null) archive(holder, dir, r.getCode());
            else Files.move(target, unique(dir.resolve(ARCHIVE_DIR), r.getCode() + "_" + java.time.LocalDateTime.now().format(STAMP), ext(target)));
        }
        fileLibrary.move(f, target);
        update(refId, x -> {
            x.setFileId(f.getId());
            x.setPendingFileId(null);
            x.setImageStatus(ImageStatus.SUCCEEDED);
            x.setImageError(null);
            x.setBlockedReason(null);
        });
        log.info("Approved reference image {} -> {}", r.getCode(), target);
        if ("FOLDER".equals(project.getRefSaveMode()) && project.getRefSaveDir() != null) {
            try {
                Path copy = copyToUserFolder(project, r, target);
                log.info("Copied approved image {} -> {}", r.getCode(), copy);
            } catch (Exception e) {
                String msg = "Đã duyệt nhưng không chép được vào " + project.getRefSaveDir() + ": " + e.getMessage();
                update(refId, x -> x.setImageError(msg));
                throw new IllegalStateException(msg);
            }
        }
    }

    /** Tên thư mục con theo loại mã (giống cấu trúc “Anh REF” người dùng hay dùng). */
    public static String kindFolder(ScriptReference.Kind k) {
        return switch (k) {
            case CHARACTER -> "NHAN VAT";
            case LOCATION -> "BOI CANH";
            case PROP -> "DAO CU";
            default -> "KHAC";
        };
    }

    /** Chép ảnh đã duyệt vào &lt;thư mục riêng&gt;/&lt;loại&gt;/&lt;MÃ&gt;.&lt;đuôi&gt; (tự tạo thư mục); file cũ trùng tên chuyển vào _cu/. */
    private Path copyToUserFolder(ScriptProject project, ScriptReference r, Path source) throws IOException {
        Path base = Path.of(project.getRefSaveDir());
        // Dự án khác cũng lưu vào thư mục này → tách thư mục con theo tên dự án để ảnh trùng mã không đè lên nhau
        Path norm = base.toAbsolutePath().normalize();
        boolean shared = projects.findAll().stream().anyMatch(o -> !o.getId().equals(project.getId()) && o.getRefSaveDir() != null
                && "FOLDER".equals(o.getRefSaveMode()) && Path.of(o.getRefSaveDir()).toAbsolutePath().normalize().equals(norm));
        if (shared) base = base.resolve(safeName(project.getName()));
        Path dir = base.resolve(kindFolder(r.getKind()));
        Files.createDirectories(dir);
        Path target = dir.resolve(source.getFileName().toString());
        if (Files.exists(target)) {
            Files.move(target, unique(dir.resolve(ARCHIVE_DIR), r.getCode() + "_" + java.time.LocalDateTime.now().format(STAMP), ext(target)));
        }
        Files.copy(source, target);
        return target;
    }

    static String safeName(String s) {
        String n = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "du-an" : n.length() > 80 ? n.substring(0, 80) : n;
    }

    /** Bỏ ảnh chờ duyệt (xoá file); mã giữ ảnh cũ nếu có. */
    public synchronized void reject(Long refId) {
        ScriptReference r = references.findById(refId).orElseThrow();
        if (r.getImageStatus() != ImageStatus.REVIEW) throw new IllegalStateException("Mã " + r.getCode() + " không có ảnh chờ duyệt");
        Long pending = r.getPendingFileId();
        update(refId, x -> {
            x.setPendingFileId(null);
            x.setImageStatus(ImageStatus.NONE);
            x.setImageError(null);
        });
        if (pending != null) deleteFileQuietly(pending);
    }

    /** Xoá ảnh chờ duyệt của mã trước khi tạo lại (không lưu entity – người gọi lưu). */
    public void discardPending(ScriptReference r) {
        if (r.getPendingFileId() != null) {
            deleteFileQuietly(r.getPendingFileId());
            r.setPendingFileId(null);
        }
    }

    private void archive(GeneratedFile f, Path dir, String code) throws IOException {
        Path p = fileLibrary.pathOf(f);
        fileLibrary.move(f, unique(dir.resolve(ARCHIVE_DIR), code + "_" + java.time.LocalDateTime.now().format(STAMP), ext(p)));
    }

    private void deleteFileQuietly(Long fileId) {
        try {
            GeneratedFile f = fileLibrary.existing(List.of(fileId)).stream().findFirst().orElse(null);
            if (f != null && fileLibrary.outsideOutput(f)) {
                // Ảnh trong thư mục dự án của người dùng: không xoá, cất vào _bo/ bên cạnh
                Path p = fileLibrary.pathOf(f);
                if (Files.isRegularFile(p)) {
                    String n = p.getFileName().toString();
                    String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
                    Files.move(p, unique(p.getParent().resolve("_bo"), base, ext(p)));
                }
            }
            fileLibrary.delete(fileId);

        } catch (Exception e) {
            log.warn("Cannot delete pending image {}: {}", fileId, e.getMessage());
        }
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "jpeg" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static Path unique(Path dir, String base, String ext) throws IOException {
        Files.createDirectories(dir);
        Path t = dir.resolve(base + "." + ext);
        for (int v = 2; Files.exists(t); v++) t = dir.resolve(base + "_" + v + "." + ext);
        return t;
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

    /** Có ảnh đang chờ / đang tạo / chờ duyệt trong dự án (bộ chạy video không tạm dừng dự án khi còn ảnh này). */
    public boolean hasPendingImages(Long projectId) {
        return references.countByProjectIdAndImageStatusIn(projectId, Set.of(ImageStatus.PENDING, ImageStatus.GENERATING, ImageStatus.REVIEW)) > 0;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
