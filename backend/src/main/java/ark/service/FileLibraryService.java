package ark.service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.stream.Stream;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.dto.GeneratedFileDto;
import ark.dto.PageResponse;
import ark.entity.GeneratedFile;
import ark.entity.MediaKind;
import ark.integration.byteplus.Media;
import ark.integration.byteplus.SavedOutput;
import ark.repository.GeneratedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Local library: files in the output folder tracked in the generated_file table. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileLibraryService {
    public static final String FILE_REF_PREFIX = "file:";
    private static final List<String> FOLDERS = List.of("images", "videos", "assets");

    private final Config config;
    private final GeneratedFileRepository repository;
    private final ark.repository.ScriptProjectRepository projects;

    public Path root() {
        return config.outputDir().toAbsolutePath().normalize();
    }

    /**
     * Đường dẫn trong thư mục output (chặn ../), hoặc đường dẫn đầy đủ nằm trong thư mục của một dự án theo thư mục
     * (ảnh tham chiếu / video lưu ngay trong thư mục dự án).
     */
    public Path resolve(String rel) {
        String r = rel.replace('\\', '/');
        if (isAbsolute(r)) {
            Path p = Path.of(r).toAbsolutePath().normalize();
            if (!insideProjectRoot(p)) throw new IllegalArgumentException("Đường dẫn không thuộc thư mục dự án nào: " + rel);
            return p;
        }
        Path p = root().resolve(r).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("Đường dẫn không hợp lệ: " + rel);
        return p;
    }

    /** Tương đối với thư mục output; file ngoài output (trong thư mục dự án) giữ đường dẫn đầy đủ. */
    public String relative(Path p) {
        Path a = p.toAbsolutePath().normalize();
        if (a.startsWith(root())) return root().relativize(a).toString().replace('\\', '/');
        return a.toString().replace('\\', '/');
    }

    /** File nằm ngoài thư mục output của app (trong thư mục dự án của người dùng) – app không bao giờ xoá file này. */
    public boolean outsideOutput(GeneratedFile f) {
        return isAbsolute(f.getPath().replace('\\', '/'));
    }

    private static boolean isAbsolute(String r) {
        return r.matches("^[A-Za-z]:/.*") || r.startsWith("//") || r.startsWith("/");
    }

    private volatile List<Path> rootsCache = List.of();
    private volatile long rootsAt;

    private boolean insideProjectRoot(Path p) {
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1 || System.currentTimeMillis() - rootsAt > 10_000) { // lần 2: dự án vừa mở – làm mới danh sách
                rootsCache = projects.findAll().stream().filter(ark.entity.ScriptProject::isFolderProject)
                        .map(x -> Path.of(x.getRootDir()).toAbsolutePath().normalize()).toList();
                rootsAt = System.currentTimeMillis();
            }
            for (Path r : rootsCache) if (p.startsWith(r)) return true;
        }
        return false;
    }

    /** Saves / updates the DB row for a freshly downloaded output. */
    public GeneratedFile record(SavedOutput out, String model, String prompt, String taskId, String jobId) {
        String rel = relative(out.file());
        GeneratedFile f = repository.findByPath(rel).orElseGet(GeneratedFile::new);
        f.setPath(rel);
        f.setKind(MediaKind.fromFileName(rel));
        f.setSourceUrl(out.sourceUrl());
        f.setCreatedAt(Instant.now());
        f.setModel(model);
        f.setPrompt(prompt == null ? null : prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt);
        f.setTaskId(taskId);
        f.setJobId(jobId);
        if (f.getProjectId() == null) f.setProjectId(ownerOf(rel));
        return repository.save(f);
    }

    /** Như record nhưng ghi rõ dự án sở hữu (ảnh tham chiếu / video của clip). */
    public GeneratedFile recordFor(Long projectId, SavedOutput out, String model, String prompt, String taskId) {
        GeneratedFile f = record(out, model, prompt, taskId, null);
        if (projectId != null && !projectId.equals(f.getProjectId())) {
            f.setProjectId(projectId);
            f = repository.save(f);
        }
        return f;
    }

    /** Dự án sở hữu theo đường dẫn: assets/&lt;thư mục dự án&gt;/… hoặc videos/&lt;thư mục dự án&gt;/… (null nếu không khớp). */
    public Long ownerOf(String rel) {
        if (rel == null) return null;
        String[] parts = rel.replace('\\', '/').split("/");
        if (parts.length < 3 || !(parts[0].equals("assets") || parts[0].equals("videos"))) return null;
        String folder = parts[1];
        return projects.findAll().stream().filter(p -> folder.equals(p.getFolder())).map(ark.entity.ScriptProject::getId)
                .findFirst().orElse(null);
    }

    /** Ảnh của một dự án (mới nhất trước). */
    public List<GeneratedFile> imagesOf(Long projectId) {
        return repository.findByKindAndProjectIdOrderByCreatedAtDesc(MediaKind.IMAGE, projectId);
    }

    public List<GeneratedFile> filesOf(Long projectId) {
        return repository.findByProjectId(projectId);
    }

    /**
     * Sao chép file sang thư mục dir thành file riêng của dự án (khi chọn ảnh của dự án khác / ảnh lẻ): dự án kia sửa / xoá /
     * đẩy Virtual Portrait không ảnh hưởng. Giữ URL gốc + model (ảnh Seedream còn hạn vẫn được tin cậy), không giữ asset.
     */
    public GeneratedFile copyTo(GeneratedFile src, Long projectId, Path dir, String baseName) throws IOException {
        Path from = pathOf(src);
        if (!Files.isRegularFile(from)) throw new IllegalArgumentException(src.getName() + " không còn trên máy");
        String name = from.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        Files.createDirectories(dir);
        Path target = dir.resolve(baseName + ext);
        for (int v = 2; Files.exists(target); v++) target = dir.resolve(baseName + "_v" + v + ext);
        Files.copy(from, target);
        GeneratedFile f = recordFor(projectId, new SavedOutput(target, src.isSourceUrlValid() ? src.getSourceUrl() : null),
                src.getModel(), src.getPrompt(), src.getTaskId());
        if (src.isSourceUrlValid()) { // giữ hạn URL gốc
            f.setCreatedAt(src.getCreatedAt());
            f = repository.save(f);
        }
        return f;
    }

    public GeneratedFile save(GeneratedFile f) {
        return repository.save(f);
    }

    public GeneratedFile get(Long id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("Không có file " + id));
    }

    public List<GeneratedFile> getAll(List<Long> ids) {
        List<GeneratedFile> found = repository.findByIdIn(ids);
        if (found.size() != ids.size()) throw new NoSuchElementException("Một số file không còn trong thư viện");
        return found;
    }

    /** File còn trong thư viện theo ids (bỏ qua id đã xoá). */
    public List<GeneratedFile> existing(List<Long> ids) {
        return repository.findByIdIn(ids);
    }

    /** Mọi file trong thư viện (kind = null → tất cả), mới nhất trước. */
    public List<GeneratedFile> allOfKind(MediaKind kind) {
        if (kind != null) return repository.findByKindOrderByCreatedAtDesc(kind);
        return repository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    public List<GeneratedFile> byAssetId(String assetId) {
        return repository.findByAssetId(assetId);
    }

    public Path pathOf(GeneratedFile f) {
        return resolve(f.getPath());
    }

    public GeneratedFileDto toDto(GeneratedFile f) {
        Path p = pathOf(f);
        Long size = null;
        try {
            if (Files.isRegularFile(p)) size = Files.size(p);
        } catch (IOException ignored) {
            // treat as missing
        }
        return GeneratedFileDto.from(f, size);
    }

    public List<GeneratedFileDto> toDtos(List<GeneratedFile> list) {
        return list.stream().sorted(Comparator.comparing(GeneratedFile::getId)).map(this::toDto).toList();
    }

    public PageResponse<GeneratedFileDto> list(MediaKind kind, int page, int size) {
        return list(kind, null, page, size);
    }

    /** project: null = mọi file, "none" = file không thuộc dự án nào, số = file của dự án đó. */
    public PageResponse<GeneratedFileDto> list(MediaKind kind, String project, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        boolean all = project == null || project.isBlank() || project.equals("all");
        Long pid = all || project.equals("none") ? null : Long.valueOf(project);
        Page<GeneratedFile> p = repository.search(kind, all, pid, pr);
        return new PageResponse<>(p.getContent().stream().map(this::toDto).toList(), p.getTotalElements(), page, size);
    }

    /** Chuyển / đổi tên file trên đĩa và cập nhật đường dẫn trong DB (giữ nguyên URL gốc, asset ID…). */
    public GeneratedFile move(GeneratedFile f, Path target) throws IOException {
        Path from = pathOf(f);
        Files.createDirectories(target.getParent());
        if (!from.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            Files.move(from, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        f.setPath(relative(target));
        Long owner = ownerOf(f.getPath());
        if (owner != null) f.setProjectId(owner);
        return repository.save(f);
    }

    public java.util.Optional<GeneratedFile> findByPath(Path p) {
        return repository.findByPath(relative(p));
    }

    /** Xoá khỏi thư viện. File trong thư mục dự án của người dùng chỉ bỏ khỏi thư viện, không xoá trên đĩa. */
    public void delete(Long id) throws IOException {
        GeneratedFile f = get(id);
        if (!outsideOutput(f)) Files.deleteIfExists(pathOf(f));
        repository.delete(f);
    }


    /**
     * Converts a frontend reference into what the generation API accepts.
     * Allowed: http(s) URL, data: URI, asset://ID, or file:{id} (library file). For library files the
     * original API URL is preferred while still valid (keeps "trusted output" status), then the asset,
     * otherwise images/audio are read from disk and sent as base64.
     */
    public String resolveRef(String ref, MediaKind kind) {
        if (ref == null || ref.isBlank()) return null;
        String r = ref.trim();
        if (r.startsWith(FILE_REF_PREFIX)) {
            GeneratedFile f = get(Long.valueOf(r.substring(FILE_REF_PREFIX.length())));
            if (f.isSourceUrlValid()) return f.getSourceUrl();
            if (kind == MediaKind.VIDEO) {
                if (f.getAssetId() != null && "Active".equals(f.getAssetStatus())) return "asset://" + f.getAssetId();
                throw new IllegalArgumentException(f.getName() + ": video tham chiếu cần URL công khai (URL gốc đã hết hạn 24h) "
                        + "– hãy đẩy vào Virtual Portrait rồi dùng asset://");
            }
            Path p = pathOf(f);
            if (!Files.isRegularFile(p)) throw new IllegalArgumentException("File " + f.getName() + " không còn trên máy");
            return p.toString();
        }
        if (Media.isRemote(r)) return r;
        throw new IllegalArgumentException("Tham chiếu không hợp lệ (chỉ nhận URL, data:, asset:// hoặc file trong thư viện): "
                + (r.length() > 60 ? r.substring(0, 60) + "…" : r));
    }

    public List<String> resolveRefs(List<String> refs, MediaKind kind) {
        List<String> out = new ArrayList<>();
        if (refs != null) for (String r : refs) if (r != null && !r.isBlank()) out.add(resolveRef(r, kind));
        return out;
    }

    /**
     * Imports files already present in the output folder (e.g. from the old desktop app) that are not yet
     * in the DB, including original URLs / asset IDs from the legacy output/index.properties.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void importExistingFiles() {
        Properties legacy = new Properties();
        Path idx = root().resolve("index.properties");
        if (Files.exists(idx)) {
            try (Reader r = Files.newBufferedReader(idx, StandardCharsets.UTF_8)) {
                legacy.load(r);
            } catch (IOException e) {
                log.warn("Cannot read legacy index: {}", e.getMessage());
            }
        }
        int imported = 0;
        for (String folder : FOLDERS) {
            Path dir = root().resolve(folder);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(Files::isRegularFile).filter(x -> !x.toString().endsWith(".part")).toList()) {
                    String rel = relative(p);
                    if (repository.findByPath(rel).isPresent()) continue;
                    GeneratedFile f = new GeneratedFile();
                    f.setPath(rel);
                    f.setKind(MediaKind.fromFileName(rel));
                    String key = p.toAbsolutePath().normalize().toString().toLowerCase();
                    f.setSourceUrl(legacy.getProperty(key + ".url"));
                    String t = legacy.getProperty(key + ".time");
                    f.setCreatedAt(t != null ? Instant.ofEpochSecond(Long.parseLong(t)) : Files.getLastModifiedTime(p).toInstant());
                    f.setAssetId(legacy.getProperty(key + ".asset"));
                    if (f.getAssetId() != null) f.setAssetStatus("Active");
                    repository.save(f);
                    imported++;
                }
            } catch (IOException e) {
                log.warn("Cannot scan {}: {}", dir, e.getMessage());
            }
        }
        if (imported > 0) log.info("Imported {} existing file(s) into the library", imported);
        // File cũ chưa ghi dự án: nhận dự án theo thư mục chứa nó
        int owned = 0;
        for (GeneratedFile f : repository.findByProjectIdIsNull()) {
            Long owner = ownerOf(f.getPath());
            if (owner != null) {
                f.setProjectId(owner);
                repository.save(f);
                owned++;
            }
        }
        if (owned > 0) log.info("Gán dự án cho {} file thư viện theo thư mục", owned);

    }
}
