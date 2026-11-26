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

    public record OpenResult(ScriptProjectDto project, ScriptImportResult imported, ScanReport scan, boolean created) {}

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
        ProjectArkDir.write(p);
        log.info("Mở thư mục dự án {} → dự án {} ({} clip mới, {} cập nhật)", root, id, r.created(), r.updated());
        return new OpenResult(scripts.toDto(p), r, scan(root.toString(), null), existing == null);
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
