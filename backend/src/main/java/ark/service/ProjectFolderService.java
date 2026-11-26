package ark.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import ark.dto.ScriptImportResult;
import ark.dto.ScriptProjectDto;
import ark.dto.ScriptProjectUpdateRequest;
import ark.entity.ScriptProject;
import ark.exception.GlobalExceptionHandler;
import ark.repository.ScriptProjectRepository;
import ark.service.ScriptImportService.FileKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dự án theo thư mục (Kịch bản hàng loạt): chọn thư mục dự án (vd {@code KET QUA - IDE 21}) → app tự đọc các file Excel
 * trong thư mục (cảnh / ảnh tham chiếu / thoại – nhận theo tiêu đề cột), đếm ảnh trong {@code Ảnh tham chiếu/},
 * {@code Ảnh chờ duyệt/} và video trong {@code Videos/}. Không cần nhập / tải file Excel lên; file Excel chỉ được đọc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFolderService {
    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp", "bmp");
    static final Pattern VIDEO_SCENE = Pattern.compile("^(\\d{1,4})(?:[ _.(\\-].*)?\\.(mp4|mov|webm)$", Pattern.CASE_INSENSITIVE);

    private final ScriptImportService importer;
    private final ScriptService scripts;
    private final ScriptProjectRepository projects;
    private final FileLibraryService fileLibrary;
    private final ark.repository.ScriptReferenceRepository references;
    private final ark.repository.ScriptClipRepository clips;

    // ---------- thư mục con của dự án ----------

    public static final String DEFAULT_REF_DIR = "Ảnh tham chiếu";
    public static final String DEFAULT_PENDING_DIR = "Ảnh chờ duyệt";
    public static final String DEFAULT_VIDEO_DIR = "Videos";

    /** Thư mục con có sẵn khớp loại (nhận cả không dấu / hoa thường), không có thì tên mặc định (chưa tạo). */
    public static Path subDir(Path root, String kind) {
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.sorted().toList()) {
                if (!Files.isDirectory(p) || p.getFileName().toString().startsWith(".")) continue;
                String k = norm(p.getFileName().toString());
                boolean pending = k.contains("cho duyet") || k.contains("pending");
                if (kind.equals("pending") && pending) return p;
                if (kind.equals("ref") && !pending && (k.contains("tham chieu") || k.contains("reference"))) return p;
                if (kind.equals("video") && k.contains("video")) return p;
            }
        } catch (IOException e) {
            log.warn("Không đọc được thư mục {}: {}", root, e.getMessage());
        }
        return root.resolve(kind.equals("pending") ? DEFAULT_PENDING_DIR : kind.equals("ref") ? DEFAULT_REF_DIR : DEFAULT_VIDEO_DIR);
    }

    public static Path refDirOf(ScriptProject p) {
        return subDir(Path.of(p.getRootDir()), "ref");
    }

    public static Path pendingDirOf(ScriptProject p) {
        return subDir(Path.of(p.getRootDir()), "pending");
    }

    public static Path videoDirOf(ScriptProject p) {
        return subDir(Path.of(p.getRootDir()), "video");
    }

    // ---------- đồng bộ ảnh / video có sẵn trong thư mục ----------

    /** Kết quả đồng bộ: số mã được gán ảnh trong thư mục, số cảnh nhận video có sẵn. */
    public record SyncResult(int refsAssigned, int scenesDone, int scenesReopened) {}

    /**
     * Ảnh trong {@code Ảnh tham chiếu/} (tên file = mã, chỉ ngay trong thư mục – bỏ qua _cu/_bo/_loi) đưa vào thư viện của
     * dự án và gán cho mã: mã chưa có ảnh dùng được → ảnh khớp mới nhất; mã đang dùng ảnh trong thư mục mà có ảnh khớp mới
     * hơn → đổi sang ảnh mới hơn. Video trong {@code Videos/} (001.mp4, 001_(1).mp4, 001_v2.mp4…) → cảnh đó Xong.
     */
    public SyncResult sync(ScriptProject p) {
        int refs = 0, done = 0, reopened = 0;
        try {
            refs = syncImages(p);
        } catch (Exception e) {
            log.warn("Không đọc được ảnh tham chiếu trong thư mục dự án {}: {}", p.getRootDir(), GlobalExceptionHandler.describe(e));
        }
        try {
            int[] v = syncVideos(p);
            done = v[0];
            reopened = v[1];
        } catch (Exception e) {
            log.warn("Không đọc được video trong thư mục dự án {}: {}", p.getRootDir(), GlobalExceptionHandler.describe(e));
        }
        return new SyncResult(refs, done, reopened);
    }

    private record Disk(ark.entity.GeneratedFile file, Path path, Instant modified) {}

    /** File ngay trong dir có đuôi exts → bản ghi thư viện của dự án (thêm nếu chưa có; giờ tạo = giờ sửa file). */
    private List<Disk> register(ScriptProject p, Path dir, Set<String> exts) throws IOException {
        List<Disk> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile).filter(x -> exts.contains(ext(x))).toList()) {
                Instant mt = Files.getLastModifiedTime(f).toInstant();
                ark.entity.GeneratedFile g = fileLibrary.findByPath(f).orElse(null);
                if (g == null) {
                    g = fileLibrary.recordFor(p.getId(), new ark.integration.byteplus.SavedOutput(f, null), null,
                            "Có sẵn trong thư mục dự án " + p.getName(), null);
                    g.setCreatedAt(mt);
                    g = fileLibrary.save(g);
                } else if (!p.getId().equals(g.getProjectId())) {
                    g.setProjectId(p.getId());
                    g = fileLibrary.save(g);
                }
                out.add(new Disk(g, f, mt));
            }
        }
        return out;
    }

    private int syncImages(ScriptProject p) throws IOException {
        Path dir = refDirOf(p);
        List<Disk> images = register(p, dir, IMAGE_EXT);
        if (images.isEmpty()) return 0;
        Path normDir = dir.toAbsolutePath().normalize();
        int n = 0;
        for (ark.entity.ScriptReference r : references.findByProjectIdOrderByKindAscCodeAsc(p.getId())) {
            if (r.isAwaitingImage()) continue; // đang tạo / chờ duyệt ảnh AI
            Disk best = images.stream().filter(d -> AssetLibraryService.nameMatchesCode(d.path().getFileName().toString(), r.getCode()))
                    .max(java.util.Comparator.comparing(Disk::modified)).orElse(null);
            if (best == null || best.file().getId().equals(r.getFileId())) continue;
            boolean replace;
            if (r.getFileId() == null) {
                replace = true;
            } else {
                ark.entity.GeneratedFile cur = fileLibrary.existing(List.of(r.getFileId())).stream().findFirst().orElse(null);
                Path curPath = cur == null ? null : fileLibrary.pathOf(cur);
                boolean usable = curPath != null && Files.isRegularFile(curPath);
                boolean inFolder = curPath != null && normDir.equals(curPath.toAbsolutePath().normalize().getParent());
                Instant curTime = usable ? Files.getLastModifiedTime(curPath).toInstant() : Instant.EPOCH;
                // ảnh hiện tại không còn, hoặc nằm ngay trong Ảnh tham chiếu/ mà có ảnh cùng mã mới hơn
                replace = !usable || (inFolder && best.modified().isAfter(curTime));
            }
            if (replace) {
                r.setFileId(best.file().getId());
                r.setBlockedReason(null);
                references.save(r);
                n++;
            }
        }
        return n;
    }

    /** @return [số cảnh nhận video có sẵn, số cảnh Xong mà video đã bị xoá → chờ chạy lại] */
    private int[] syncVideos(ScriptProject p) throws IOException {
        Path dir = videoDirOf(p);
        List<Disk> videos = register(p, dir, Set.of("mp4", "mov", "webm"));
        Path normDir = dir.toAbsolutePath().normalize();
        int done = 0, reopened = 0;
        for (ark.entity.ScriptClip c : clips.findByProjectIdOrderBySeq(p.getId())) {
            if (c.getStatus() == ark.entity.ScriptClip.Status.GENERATING || c.getTaskId() != null) continue;
            Disk best = videos.stream().filter(d -> videoOfClip(d.path().getFileName().toString(), c.getCode()))
                    .max(java.util.Comparator.comparing(Disk::modified)).orElse(null);
            if (c.getStatus() == ark.entity.ScriptClip.Status.SUCCEEDED) {
                if (best != null && c.getFileId() == null) {
                    c.setFileId(best.file().getId());
                    clips.save(c);
                }
                if (best == null && c.getFileId() != null) {
                    ark.entity.GeneratedFile cur = fileLibrary.existing(List.of(c.getFileId())).stream().findFirst().orElse(null);
                    Path curPath = cur == null ? null : fileLibrary.pathOf(cur);
                    if (curPath == null || (!Files.exists(curPath) && normDir.equals(curPath.toAbsolutePath().normalize().getParent()))) {
                        c.setStatus(ark.entity.ScriptClip.Status.PENDING);
                        c.setFileId(null);
                        c.setError("Video trong thư mục dự án đã bị xoá – chờ tạo lại");
                        clips.save(c);
                        reopened++;
                    }
                }
                continue;
            }
            // Làm lại đã yêu cầu (ưu tiên chạy) thì giữ nguyên, không coi video cũ là xong
            if (best == null || c.getRunPriority() > 0) continue;
            c.setStatus(ark.entity.ScriptClip.Status.SUCCEEDED);
            c.setFileId(best.file().getId());
            c.setError(null);
            if (c.getFinishedAt() == null) c.setFinishedAt(best.modified());
            clips.save(c);
            done++;
        }
        return new int[] {done, reopened};
    }

    /** File video thuộc cảnh: 001.mp4 / 001_(1).mp4 / 001_v2.mp4 / 1.mp4 với cảnh 001; mã chữ (019A) thì tên bắt đầu bằng mã. */
    static boolean videoOfClip(String fileName, String code) {
        Matcher m = VIDEO_SCENE.matcher(fileName);
        if (code.matches("\\d{1,4}")) return m.matches() && Integer.parseInt(m.group(1)) == Integer.parseInt(code);
        return fileName.matches("(?i)" + Pattern.quote(code) + "(?:[ _.(\\-].*)?\\.(mp4|mov|webm)");
    }

    /** Một file Excel trong thư mục và loại nội dung nhận ra. */
    public record ExcelFile(String name, List<FileKind> kinds, String error) {}

    /** Thư mục con nhận ra (null nếu chưa có) + số file. */
    public record SubFolder(String name, int files, int scenes) {}

    /**
     * Kết quả quét: các file Excel theo loại, file đang chọn (lần trước hoặc file duy nhất), thư mục ảnh / video,
     * vấn đề cần xử lý (thiếu file cảnh, nhiều file cùng loại chưa chọn…).
     */
    public record ScanReport(String path, String name, List<ExcelFile> excels, List<String> sceneCandidates,
                             List<String> refCandidates, List<String> voiceCandidates, String sceneFile, String refFile,
                             String voiceFile, SubFolder refDir, SubFolder pendingDir, SubFolder videoDir, List<String> otherFiles,
                             List<String> problems, Long projectId, boolean canOpen) {}

    /** Lựa chọn khi mở (null = tự chọn). */
    public record Selection(String sceneFile, String refFile, String voiceFile) {}

    public record OpenResult(ScriptProjectDto project, ScriptImportResult imported, ScanReport scan, boolean created, SyncResult synced) {}


    // ---------- quét ----------

    public ScanReport scan(String path, Selection sel) {
        Path root = dir(path);
        Map<String, Object> saved = ProjectArkDir.read(root.toString());
        ScriptProject existing = byRoot(root);

        List<ExcelFile> excels = new ArrayList<>();
        List<String> others = new ArrayList<>();
        SubFolder refDir = null, pendingDir = null, videoDir = null;
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    String k = norm(n);
                    if (n.startsWith(".")) continue;
                    if (pendingDir == null && (k.contains("cho duyet") || k.contains("pending"))) pendingDir = images(p);
                    else if (refDir == null && (k.contains("tham chieu") || k.contains("reference"))) refDir = images(p);
                    else if (videoDir == null && k.contains("video")) videoDir = videos(p);
                    continue;
                }
                if (n.startsWith("~$")) continue;
                if (n.toLowerCase(Locale.ROOT).endsWith(".xlsx")) excels.add(classify(p));
                else others.add(n);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Không đọc được thư mục " + root + ": " + e.getMessage());
        }

        List<String> scenes = names(excels, FileKind.SCENES);
        List<String> refs = names(excels, FileKind.REFERENCES);
        List<String> voice = names(excels, FileKind.VOICE);
        String sceneFile = pick(sel == null ? null : sel.sceneFile(), existing == null ? null : existing.getSceneFile(),
                ProjectArkDir.savedFile(saved, "scenes"), scenes);
        String refFile = pick(sel == null ? null : sel.refFile(), existing == null ? null : existing.getRefFile(),
                ProjectArkDir.savedFile(saved, "references"), refs);
        String voiceFile = pick(sel == null ? null : sel.voiceFile(), existing == null ? null : existing.getVoiceFile(),
                ProjectArkDir.savedFile(saved, "voice"), voice);
        // File kịch bản kiểu cũ có cả cảnh lẫn mã ảnh: chọn nó làm file cảnh thì cũng là file ảnh tham chiếu
        if (refFile == null && sceneFile != null && refs.contains(sceneFile)) refFile = sceneFile;

        List<String> problems = new ArrayList<>();
        if (scenes.isEmpty()) {
            problems.add("Không thấy file Excel danh sách cảnh (cần cột “STT segment” + “Reference IDs”, hoặc sheet KỊCH BẢN)");
        } else if (sceneFile == null) {
            problems.add("Có " + scenes.size() + " file Excel danh sách cảnh – chọn file dùng cho dự án");
        }
        if (refFile == null && refs.size() > 1) problems.add("Có " + refs.size() + " file Excel ảnh tham chiếu – chọn file dùng (hoặc để trống)");
        for (ExcelFile f : excels) if (f.error() != null) problems.add(f.name() + ": " + f.error());

        return new ScanReport(root.toString(), root.getFileName() == null ? root.toString() : root.getFileName().toString(),
                excels, scenes, refs, voice, sceneFile, refFile, voiceFile, refDir, pendingDir, videoDir, others, problems,
                existing == null ? null : existing.getId(), sceneFile != null);
    }

    private static String pick(String chosen, String current, String saved, List<String> candidates) {
        for (String c : new String[] {chosen, current, saved}) {
            if (c != null && !c.isBlank() && candidates.contains(c)) return c;
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static List<String> names(List<ExcelFile> excels, FileKind k) {
        return excels.stream().filter(f -> f.kinds().contains(k)).map(ExcelFile::name).toList();
    }

    private static ExcelFile classify(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            EnumSet<FileKind> kinds = ScriptImportService.classify(in);
            return new ExcelFile(p.getFileName().toString(), List.copyOf(kinds), kinds.isEmpty() ? "không nhận ra nội dung (bỏ qua)" : null);
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            if (m.toLowerCase(Locale.ROOT).contains("being used") || m.contains("process cannot access")) m = "file đang mở trong Excel – đóng Excel rồi quét lại";
            return new ExcelFile(p.getFileName().toString(), List.of(), m);
        }
    }

    /** Ảnh ở ngay trong thư mục (không tính thư mục con _cu, _bo, _loi…). */
    private static SubFolder images(Path d) throws IOException {
        int n = 0;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.toList()) if (Files.isRegularFile(p) && IMAGE_EXT.contains(ext(p))) n++;
        }
        return new SubFolder(d.getFileName().toString(), n, 0);
    }

    /** Video ở ngay trong thư mục + số cảnh khác nhau có video (theo số đầu tên file: 001.mp4, 001_(1).mp4, 001_v2.mp4…). */
    private static SubFolder videos(Path d) throws IOException {
        int n = 0;
        Set<Integer> scenes = new LinkedHashSet<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.toList()) {
                if (!Files.isRegularFile(p)) continue;
                Matcher m = VIDEO_SCENE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    n++;
                    scenes.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        return new SubFolder(d.getFileName().toString(), n, scenes.size());
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Bỏ dấu, chữ thường – so tên thư mục "Ảnh chờ duyệt" / "Anh cho duyet" như nhau. */
    static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase(Locale.ROOT);
    }

    private static Path dir(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Chưa chọn thư mục dự án");
        String p = path.trim();
        if (p.length() > 1 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
        Path root;
        try {
            root = Path.of(p).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalArgumentException("Đường dẫn không hợp lệ: " + p);
        }
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Không có thư mục " + root);
        return root;
    }

    private ScriptProject byRoot(Path root) {
        String key = root.toString().toLowerCase(Locale.ROOT);
        return projects.findAll().stream().filter(ScriptProject::isFolderProject)
                .filter(x -> Path.of(x.getRootDir()).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT).equals(key))
                .findFirst().orElse(null);
    }

    // ---------- mở / quét lại ----------

    /**
     * Mở thư mục dự án: đọc file Excel đã chọn (cảnh + ảnh tham chiếu + thoại) ngay trong thư mục. Thư mục đã từng mở thì
     * cập nhật vào dự án cũ (giữ trạng thái clip, ảnh đã gán), chưa thì tạo dự án mới và lấy lại cài đặt trong .ark/.
     */
    public synchronized OpenResult open(String path, Selection sel) throws IOException {
        ScanReport scan = scan(path, sel);
        if (!scan.canOpen()) throw new IllegalArgumentException(String.join(". ", scan.problems()));
        Path root = Path.of(scan.path());
        ScriptProject existing = byRoot(root);
        if (existing != null && existing.getStatus() == ScriptProject.Status.RUNNING) {
            throw new IllegalStateException("Dự án đang chạy – tạm dừng rồi mới quét lại");
        }
        List<String> files = new ArrayList<>();
        for (String f : new String[] {scan.sceneFile(), scan.refFile(), scan.voiceFile()}) {
            if (f != null && !files.contains(f)) files.add(f);
        }
        List<InputStream> opened = new ArrayList<>();
        ScriptImportResult r;
        try {
            List<ScriptImportService.Source> sources = new ArrayList<>();
            for (String f : files) {
                InputStream in = Files.newInputStream(root.resolve(f));
                opened.add(in);
                sources.add(new ScriptImportService.Source(in, f));
            }
            r = importer.importFiles(sources, existing == null ? null : existing.getId(), existing == null ? scan.name() : null);
        } finally {
            for (InputStream in : opened) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // đã đọc xong
                }
            }
        }
        Long id = r.project().id();
        ScriptProject p = projects.findById(id).orElseThrow();
        p.setRootDir(root.toString());
        p.setSceneFile(scan.sceneFile());
        p.setRefFile(scan.refFile());
        p.setVoiceFile(scan.voiceFile());
        p.setSourceFile(scan.sceneFile());
        p.setLastOpenedAt(Instant.now());
        projects.save(p);
        if (existing == null) {
            ScriptProjectUpdateRequest saved = ProjectArkDir.savedSettings(ProjectArkDir.read(root.toString()));
            if (saved != null) {
                try {
                    scripts.update(id, saved);
                    log.info("Dự án {}: lấy lại cài đặt từ {}/project.json", id, ProjectArkDir.DIR);
                } catch (Exception e) {
                    log.warn("Cài đặt trong .ark/project.json không dùng được: {}", GlobalExceptionHandler.describe(e));
                }
            }
        }
        p = projects.findById(id).orElseThrow();
        try {
            Files.createDirectories(videoDirOf(p)); // thư mục chứa video của dự án
        } catch (IOException e) {
            log.warn("Không tạo được thư mục video trong {}: {}", root, e.getMessage());
        }
        SyncResult synced = sync(p);
        log.info("Dự án {}: gán {} mã theo ảnh trong thư mục, {} cảnh đã có video, {} cảnh mất video", id,
                synced.refsAssigned(), synced.scenesDone(), synced.scenesReopened());
        ProjectArkDir.write(p);
        log.info("Mở thư mục dự án {} → dự án {} ({} clip mới, {} cập nhật)", root, id, r.created(), r.updated());
        return new OpenResult(scripts.toDto(projects.findById(id).orElseThrow()), r, scan(root.toString(), null), existing == null, synced);
    }

    /** Quét lại dự án theo thư mục (đọc lại Excel đã chọn, đếm lại ảnh / video). */
    public OpenResult rescan(Long id) throws IOException {
        ScriptProject p = projects.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("Không có dự án " + id));
        if (!p.isFolderProject()) throw new IllegalArgumentException("Dự án này nhập từ Excel – dùng “Nhập lại vào dự án này”");
        return open(p.getRootDir(), new Selection(p.getSceneFile(), p.getRefFile(), p.getVoiceFile()));
    }

    /** Dự án theo thư mục, mở gần nhất trước (thư mục không còn thì đánh dấu missing). */
    public List<Map<String, Object>> recent() {
        return projects.findAll().stream().filter(ScriptProject::isFolderProject)
                .sorted((a, b) -> {
                    Instant x = a.getLastOpenedAt() == null ? a.getCreatedAt() : a.getLastOpenedAt();
                    Instant y = b.getLastOpenedAt() == null ? b.getCreatedAt() : b.getLastOpenedAt();
                    return y == null ? -1 : x == null ? 1 : y.compareTo(x);
                })
                .map(p -> Map.<String, Object>of("id", p.getId(), "name", p.getName(), "path", p.getRootDir(),
                        "exists", Files.isDirectory(Path.of(p.getRootDir()))))
                .toList();
    }
}
