package ark.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.dto.CreateAssetGroupRequest;
import ark.dto.JobDto;
import ark.dto.PageResponse;
import ark.dto.PushFilesRequest;
import ark.dto.PushUrlRequest;
import ark.entity.GeneratedFile;
import ark.entity.MediaKind;
import ark.exception.GlobalExceptionHandler;
import ark.integration.byteplus.ArkClient;
import ark.integration.byteplus.AssetApi;
import ark.integration.byteplus.Json;
import ark.integration.byteplus.SavedOutput;
import lombok.RequiredArgsConstructor;

/**
 * Virtual Portrait: push generated files (by their original URL), wait until Active,
 * keep the asset ID on the library file so it can be reused as asset://ID.
 */
@Service
@RequiredArgsConstructor
public class AssetLibraryService {
    private final Config config;
    private final SettingsService settings;
    private final FileLibraryService fileLibrary;
    private final JobService jobs;
    private final ark.repository.ScriptProjectRepository projects;

    private final Object bucketLock = new Object();
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> fileLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /** Đủ cấu hình để tự đẩy Virtual Portrait: AK/SK + asset group. */
    public boolean vpReady() {
        return !config.accessKey().isEmpty() && !config.secretKey().isEmpty() && !config.assetGroup().isEmpty();
    }

    /** Khoá phụ gắn vào mỗi asset trả về từ {@link #activeAssets()}: tên asset group chứa asset. */
    public static final String GROUP_NAME = "_GroupName";

    /**
     * Mọi asset Active trong MỌI asset group (AIGC) của project, mới nhất trước (100/trang, tối đa 5000).
     * Mỗi asset kèm tên group ({@link #GROUP_NAME}) – người dùng hay tạo mỗi nhân vật một group đặt tên theo mã.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> activeAssets() throws IOException, InterruptedException {
        AssetApi api = api();
        Map<String, String> groupNames = new java.util.HashMap<>();
        for (Map<String, Object> g : api.listGroups()) groupNames.put(Json.str(g, "Id"), Json.str(g, "Name"));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int page = 1; page <= 50; page++) {
            List<?> items = Json.list(api.listAssets(null, null, List.of("Active"), page, 100), "Items");
            for (Object o : items) {
                Map<String, Object> a = new java.util.LinkedHashMap<>((Map<String, Object>) o);
                a.put(GROUP_NAME, groupNames.get(Json.str(a, "GroupId")));
                out.add(a);
            }
            if (items.size() < 100) break;
        }
        return out;
    }

    private volatile List<Map<String, Object>> assetCache = List.of();
    private volatile long assetCacheAt;

    /** {@link #activeAssets()} có cache 60 giây (force = tải lại ngay). Lỗi mạng → giữ cache cũ. */
    public synchronized List<Map<String, Object>> activeAssetsCached(boolean force) throws IOException, InterruptedException {
        if (force || System.currentTimeMillis() - assetCacheAt > 60_000) {
            assetCache = activeAssets();
            assetCacheAt = System.currentTimeMillis();
        }
        return assetCache;
    }

    /** Bỏ các asset đã bị xoá trên BytePlus khỏi cache (để không ghép lại đúng asset đó theo tên). */
    public synchronized void dropFromCache(java.util.Collection<String> assetIds) {
        assetCache = assetCache.stream().filter(a -> !assetIds.contains(String.valueOf(a.get("Id")))).toList();
    }

    /** Chỉ đọc cache (không gọi mạng), chỉ asset dự án được dùng ({@link #findAssetFor(List, String, ark.entity.ScriptProject)}). */
    public Map<String, Object> cachedAssetFor(String code, ark.entity.ScriptProject project) {
        cachedAssetFor(code);
        return findAssetFor(assetCache, code, project);
    }

    /**
     * Asset Active khớp mã mà dự án được dùng, theo thứ tự:
     * <ol>
     * <li>asset trong asset group riêng của dự án, tên trùng mã;</li>
     * <li>asset tên "&lt;thư mục dự án&gt;__&lt;mã&gt;" (đẩy vào group chung khi không tạo được group riêng);</li>
     * <li>asset không thuộc dự án nào khác (vd tự tải lên console, group tên = mã): không nằm trong group riêng của dự án khác,
     * không mang tiền tố "&lt;thư mục&gt;__", không gắn với file thư viện của dự án khác.</li>
     * </ol>
     * Không bao giờ trả về asset của dự án khác – nhiều dự án trùng mã là ảnh khác nhau.
     */
    public Map<String, Object> findAssetFor(List<Map<String, Object>> assets, String code, ark.entity.ScriptProject project) {
        if (project == null) return findAssetFor(assets, code);
        String own = project.getVpGroupId();
        if (own != null) {
            for (Map<String, Object> a : assets) {
                if (own.equals(Json.str(a, "GroupId")) && nameMatchesCode(Json.str(a, "Name"), code)) return a;
            }
        }
        String prefixed = project.getFolder() + "__" + code;
        for (Map<String, Object> a : assets) if (nameMatchesCode(Json.str(a, "Name"), prefixed)) return a;
        java.util.Set<String> otherGroups = new java.util.HashSet<>();
        for (ark.entity.ScriptProject o : projects.findAll()) {
            if (!o.getId().equals(project.getId()) && o.getVpGroupId() != null) otherGroups.add(o.getVpGroupId());
        }
        java.util.function.Predicate<Map<String, Object>> free = a -> {
            if (otherGroups.contains(Json.str(a, "GroupId"))) return false;
            String n = Json.str(a, "Name");
            if (n != null && n.contains("__")) return false;
            for (GeneratedFile f : fileLibrary.byAssetId(Json.str(a, "Id"))) {
                if (f.getProjectId() != null && !f.getProjectId().equals(project.getId())) return false;
            }
            return true;
        };
        for (Map<String, Object> a : assets) if (nameMatchesCode(Json.str(a, "Name"), code) && free.test(a)) return a;
        for (Map<String, Object> a : assets) if (nameMatchesCode((String) a.get(GROUP_NAME), code) && free.test(a)) return a;
        return null;
    }

    /** Chỉ đọc cache (không gọi mạng) – dùng ở chỗ chạy thường xuyên như bộ điều phối clip. */
    public Map<String, Object> cachedAssetFor(String code) {
        if (System.currentTimeMillis() - assetCacheAt > 60_000 && hasKeys() && refreshing.compareAndSet(false, true)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    activeAssetsCached(false);
                } catch (Exception e) {
                    assetCacheAt = System.currentTimeMillis(); // lỗi (vd thiếu quyền) → thử lại sau 60s, không spam
                } finally {
                    refreshing.set(false);
                }
            });
        }
        return findAssetFor(assetCache, code);
    }

    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    public boolean hasKeys() {
        return !config.accessKey().isEmpty() && !config.secretKey().isEmpty();
    }

    /** Asset khớp mã: theo tên asset trước, rồi theo tên asset group (mỗi nhân vật một group tên = mã); mới nhất trước. */
    public static Map<String, Object> findAssetFor(List<Map<String, Object>> assets, String code) {
        for (Map<String, Object> a : assets) if (nameMatchesCode(Json.str(a, "Name"), code)) return a;
        for (Map<String, Object> a : assets) if (nameMatchesCode((String) a.get(GROUP_NAME), code)) return a;
        return null;
    }

    /** Tên file / asset / group trùng mã: CHAR_X_01, CHAR_X_01.jpg, CHAR_X_01_v2.png, "char_x_01 final.jpg"… */
    public static boolean nameMatchesCode(String name, String code) {
        if (name == null || name.isBlank()) return false;
        String c = code.toLowerCase(java.util.Locale.ROOT);
        String stem = name.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("\\.[^.]+$", "");
        return stem.equals(c) || stem.matches(java.util.regex.Pattern.quote(c) + "([ _.-](v\\d+|\\d+|final|ok))?");
    }

    /**
     * File thư viện gắn với asset Active: dùng lại file đã tải nếu còn trên máy, nếu không thì tải về
     * dir/&lt;baseName&gt;_vp.&lt;đuôi&gt; (không ghi đè ảnh gốc cùng tên) và lưu asset ID.
     */
    public GeneratedFile fileForAsset(Map<String, Object> a, Path dir, String baseName) throws IOException, InterruptedException {
        return fileForAsset(a, dir, baseName, null);
    }

    /** Như trên cho dự án projectId: chỉ dùng lại file của chính dự án (hoặc chưa thuộc dự án nào), file mới ghi thuộc dự án. */
    public GeneratedFile fileForAsset(Map<String, Object> a, Path dir, String baseName, Long projectId) throws IOException, InterruptedException {
        String id = Json.str(a, "Id");
        for (GeneratedFile f : fileLibrary.byAssetId(id)) {
            if (projectId != null && f.getProjectId() != null && !projectId.equals(f.getProjectId())) continue;
            if (java.nio.file.Files.isRegularFile(fileLibrary.pathOf(f))) {
                if (projectId != null && f.getProjectId() == null) {
                    f.setProjectId(projectId);
                    f = fileLibrary.save(f);
                }
                if (!"Active".equals(f.getAssetStatus())) {
                    f.setAssetStatus("Active");
                    f.setAssetError(null);
                    f = fileLibrary.save(f);
                }
                return f;
            }
        }
        String url = Json.str(a, "URL");
        if (url == null) throw new IllegalStateException("Asset " + id + " chưa có URL để tải về");
        String p = URI.create(url).getPath();
        String ext = p != null && p.lastIndexOf('.') > p.lastIndexOf('/') ? p.substring(p.lastIndexOf('.')) : ".jpg";
        Path target = ArkClient.from(config).download(url, dir.resolve(baseName + "_vp" + ext));
        GeneratedFile f = fileLibrary.recordFor(projectId, new SavedOutput(target, null), null, Json.str(a, "Name"), null);
        f.setAssetId(id);
        f.setAssetStatus("Active");
        f.setAssetError(null);
        return fileLibrary.save(f);
    }

    /** TOS từ chối (vd tài khoản chưa bật TOS) → nhớ 15 phút, không thử tạo bucket lại cho từng ảnh / từng clip. */
    private static final long TOS_RETRY_MS = 15 * 60_000L;
    private volatile String tosError;
    private volatile long tosErrorAt;

    /** Lý do không tải được ảnh trên máy lên TOS (null = dùng được / chưa biết). */
    public String tosUnavailable() {
        String e = tosError;
        if (e != null && System.currentTimeMillis() - tosErrorAt > TOS_RETRY_MS) {
            tosError = null;
            return null;
        }
        return e;
    }

    /**
     * Thử TOS ngay (kiểm tra / tạo bucket riêng tư, không tải ảnh nào): null = dùng được, còn lại là lý do bằng tiếng Việt.
     * Lỗi được nhớ 15 phút như khi chạy thật.
     */
    public String probeTos(boolean fresh) {
        if (!hasKeys()) return "Chưa nhập AK/SK BytePlus (Cài đặt) nên không tải được ảnh lên TOS";
        if (fresh) tosError = null; // người dùng vừa bật TOS và bấm Kiểm tra lại
        String known = tosUnavailable();
        if (known != null) return known;
        try {
            ark.integration.byteplus.TosClient tos = ark.integration.byteplus.TosClient.of(config.accessKey(), config.secretKey(), config.tosRegion());
            ensureBucket(tos, m -> { });
            return null;
        } catch (IOException e) {
            String hint = tosHint(String.valueOf(e.getMessage()));
            if (hint == null) return "Không kết nối được TOS: " + e.getMessage();
            tosError = hint;
            tosErrorAt = System.currentTimeMillis();
            return hint;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Đẩy được file này lên Virtual Portrait: đủ cấu hình VP và (URL gốc còn hạn hoặc TOS dùng được). */
    public boolean canPush(GeneratedFile f) {
        return vpReady() && (f.isSourceUrlValid() || tosUnavailable() == null);
    }

    private static String tosHint(String raw) {
        if (raw.contains("AccountDisable") || raw.contains("does not open tos")) {
            return "Tài khoản BytePlus chưa bật dịch vụ TOS (Object Storage) nên app không tải được ảnh trên máy lên TOS. "
                    + "Bật TOS tại console.byteplus.com → Object Storage (TOS) → Activate, rồi thử lại sau vài phút";
        }
        if (raw.contains("HTTP 403")) {
            return "AK/SK không có quyền TOS (" + raw + "). Cấp quyền TOS cho IAM user trong console rồi thử lại";
        }
        return null;
    }

    /**
     * URL công khai cho CreateAsset: URL gốc của model nếu còn hạn, nếu không thì tải file trên máy lên bucket TOS riêng tư
     * (app tự tạo lần đầu) và trả về URL có chữ ký 6 giờ.
     */
    public String publicUrl(GeneratedFile f, java.util.function.Consumer<String> log) throws Exception {
        if (f.isSourceUrlValid()) return f.getSourceUrl();
        return tosUrl(f, "ark-studio/" + java.time.LocalDate.now(), java.time.Duration.ofHours(6), log);
    }

    /**
     * Tải file trên máy lên bucket TOS riêng tư của app (tự tạo lần đầu) dưới thư mục prefix, trả về URL có chữ ký
     * sống trong ttl (TOS cho tối đa 7 ngày). Không đụng tới Virtual Portrait.
     */
    public String tosUrl(GeneratedFile f, String prefix, java.time.Duration ttl, java.util.function.Consumer<String> log) throws Exception {
        if (!hasKeys()) throw new IllegalStateException("Chưa nhập AK/SK BytePlus (Cài đặt) nên không tải được ảnh lên TOS");
        Path p = fileLibrary.pathOf(f);
        if (!java.nio.file.Files.isRegularFile(p)) throw new IllegalStateException(f.getName() + " không còn trên máy");
        String known = tosUnavailable();
        if (known != null) throw new IllegalStateException(known);
        ark.integration.byteplus.TosClient tos = ark.integration.byteplus.TosClient.of(config.accessKey(), config.secretKey(), config.tosRegion());
        String bucket;
        try {
            bucket = ensureBucket(tos, log);
        } catch (IOException e) {
            String hint = tosHint(String.valueOf(e.getMessage()));
            if (hint == null) throw e;
            tosError = hint;
            tosErrorAt = System.currentTimeMillis();
            throw new IllegalStateException(hint, e);
        }
        String key = prefix + "/" + f.getId() + "_" + f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        String name = f.getName().toLowerCase(java.util.Locale.ROOT);
        String type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : name.endsWith(".mp4") ? "video/mp4"
                : name.endsWith(".mp3") ? "audio/mpeg" : name.endsWith(".wav") ? "audio/wav" : "image/jpeg";
        log.accept("Tải " + f.getName() + " lên TOS (" + bucket + ")…");
        tos.putObject(bucket, key, java.nio.file.Files.readAllBytes(p), type);
        return tos.presignGet(bucket, key, ttl);
    }

    private String ensureBucket(ark.integration.byteplus.TosClient tos, java.util.function.Consumer<String> log) throws Exception {
        synchronized (bucketLock) {
            String known = tosUnavailable(); // luồng khác vừa gặp lỗi TOS khi đang chờ khoá
            if (known != null) throw new IllegalStateException(known);
            String b = config.tosBucket();
            if (!b.isEmpty() && tos.bucketExists(b)) return b;
            if (b.isEmpty()) {
                String suffix = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
                b = "ark-studio-" + suffix.toLowerCase(java.util.Locale.ROOT);
            }
            log.accept("Tạo bucket TOS riêng tư " + b + " (" + config.tosRegion() + ") để chứa ảnh tải lên…");
            tos.createBucket(b);
            config.setTosBucket(b);
            config.save();
            return b;
        }
    }

    /**
     * Bảo đảm file có asset Virtual Portrait Active (tải lên TOS nếu là file trên máy). Mỗi file chỉ đẩy một lần dù nhiều
     * clip cùng cần. Trả về true nếu Active.
     */
    public boolean ensureActiveAsset(GeneratedFile file, java.util.function.Consumer<String> log,
                                     java.util.function.BooleanSupplier cancelled) throws Exception {
        return ensureActiveAsset(file, null, log, cancelled);
    }

    /** Không tạo được group riêng (vd giới hạn số group) → nhớ 1 giờ, dùng group chung + tên "&lt;thư mục&gt;__&lt;tên file&gt;". */
    private volatile long groupCreateFailedAt;

    /**
     * Asset group Virtual Portrait riêng của dự án (tự tạo lần đầu, lưu vào dự án). null = dùng group chung trong Cài đặt.
     */
    public String projectGroup(ark.entity.ScriptProject project, java.util.function.Consumer<String> log) {
        if (project == null) return null;
        synchronized (bucketLock) {
            ark.entity.ScriptProject fresh = projects.findById(project.getId()).orElse(project);
            if (fresh.getVpGroupId() != null) return fresh.getVpGroupId();
            if (System.currentTimeMillis() - groupCreateFailedAt < 3_600_000L) return null;
            String name = (fresh.getName() + " (" + fresh.getFolder() + ")");
            if (name.length() > 60) name = name.substring(0, 60);
            try {
                String id = api().createGroup(name, "Ark Studio – ảnh tham chiếu của dự án " + fresh.getName());
                fresh.setVpGroupId(id);
                projects.save(fresh);
                project.setVpGroupId(id);
                log.accept("Tạo asset group Virtual Portrait riêng cho dự án: " + name);
                return id;
            } catch (Exception e) {
                groupCreateFailedAt = System.currentTimeMillis();
                log.accept("Không tạo được asset group riêng (" + GlobalExceptionHandler.describe(e)
                        + ") – dùng group chung, tên asset kèm thư mục dự án");
                return null;
            }
        }
    }

    /** Đẩy file của dự án vào group riêng của dự án (hoặc group chung với tên "&lt;thư mục&gt;__&lt;tên file&gt;"). */
    public boolean ensureActiveAsset(GeneratedFile file, ark.entity.ScriptProject project, java.util.function.Consumer<String> log,
                                     java.util.function.BooleanSupplier cancelled) throws Exception {
        Object lock = fileLocks.computeIfAbsent(file.getId(), k -> new Object());
        synchronized (lock) {
            GeneratedFile f = fileLibrary.get(file.getId());
            if (f.getAssetId() != null && "Active".equals(f.getAssetStatus())) return true;
            if (!vpReady()) throw new IllegalStateException("Chưa đủ cấu hình Virtual Portrait (AK/SK trong Cài đặt + asset group ở trang Virtual Portrait)");
            if (f.getAssetId() != null && "Processing".equals(f.getAssetStatus())) {
                Map<String, Object> a = api().waitActive(f.getAssetId(), log, cancelled);
                if (a != null) {
                    f.setAssetStatus(Json.str(a, "Status"));
                    if ("Failed".equals(f.getAssetStatus())) f.setAssetError(Json.str(a, "Error", "Code") + ": " + Json.str(a, "Error", "Message"));
                    fileLibrary.save(f);
                }
                return "Active".equals(f.getAssetStatus());
            }
            String group = project == null ? null : projectGroup(project, log);
            if (group != null) return pushFile(f, api(), group, f.getName(), log, cancelled);
            String name = project == null ? f.getName() : project.getFolder() + "__" + f.getName();
            return pushFile(f, api(), config.assetGroup(), name, log, cancelled);
        }
    }

    private AssetApi api() {
        return AssetApi.from(config);
    }

    private String group(String requested) {
        String g = requested == null || requested.isBlank() ? config.assetGroup() : requested;
        if (g.isBlank()) throw new IllegalArgumentException("Chưa chọn asset group (trang Virtual Portrait)");
        return g;
    }

    public List<Map<String, Object>> listGroups() throws IOException, InterruptedException {
        return api().listGroups();
    }

    /** Creates a group and makes it the default push target. */
    public Map<String, Object> createGroup(CreateAssetGroupRequest req) throws IOException, InterruptedException {
        String id = api().createGroup(req.getName().trim(),
                req.getDescription() == null ? "Created by BytePlus Ark Studio" : req.getDescription());
        settings.setAssetGroup(id);
        return Map.of("id", id);
    }

    @SuppressWarnings("unchecked")
    public PageResponse<Map<String, Object>> listAssets(String group, String name, String status, int page, int size)
            throws IOException, InterruptedException {
        String g = group == null ? config.assetGroup() : group;
        Map<String, Object> r = api().listAssets(g, name, status == null || status.isBlank() ? null : List.of(status), page, size);
        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) Json.list(r, "Items");
        Long total = Json.lng(r, "TotalCount");
        return new PageResponse<>(items, total == null ? items.size() : total, page, size);
    }

    public Map<String, Object> getAsset(String id) throws IOException, InterruptedException {
        return api().getAsset(id);
    }

    public void deleteAsset(String id) throws IOException, InterruptedException {
        api().deleteAsset(id);
        for (GeneratedFile f : fileLibrary.byAssetId(id)) {
            f.setAssetId(null);
            f.setAssetStatus(null);
            f.setAssetError(null);
            fileLibrary.save(f);
        }
    }

    public JobDto pushFiles(PushFilesRequest req) {
        String group = group(req.getGroup());
        List<GeneratedFile> files = fileLibrary.getAll(req.getFileIds());
        AssetApi api = api();
        return jobs.start("asset-push", "Đẩy " + files.size() + " file vào Virtual Portrait", ctx -> {
            int ok = 0;
            for (GeneratedFile f : files) {
                try {
                    if (pushFile(f, api, group, ctx)) ok++;
                } catch (Exception e) {
                    ctx.log("LỖI " + f.getName() + ": " + GlobalExceptionHandler.describe(e));
                }
                ctx.addFile(f);
            }
            ctx.log("Virtual Portrait: " + ok + "/" + files.size() + " file Active");
            if (ok == 0 && !ctx.isCancelled()) throw new IllegalStateException("Không đẩy được file nào – xem log");
        });
    }

    public JobDto pushUrl(PushUrlRequest req) {
        String group = group(req.getGroup());
        String url = req.getUrl().trim();
        String path = URI.create(url).getPath();
        String fileName = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        String name = req.getName() != null && !req.getName().isBlank() ? req.getName() : (fileName.isEmpty() ? "url-asset" : fileName);
        String type = req.getType() != null && !req.getType().isBlank() ? req.getType()
                : AssetApi.assetType(fileName.isEmpty() ? "x.jpg" : fileName);
        AssetApi api = api();
        return jobs.start("asset-push", "Đẩy URL vào Virtual Portrait", ctx -> {
            String id = api.createAsset(group, url, type, name);
            ctx.log("Đã gửi " + type + ": " + id);
            Map<String, Object> a = api.waitActive(id, ctx::log, ctx::isCancelled);
            if (a != null && "Failed".equals(Json.str(a, "Status"))) {
                throw new IllegalStateException("Asset thất bại: " + Json.str(a, "Error", "Code") + " – " + Json.str(a, "Error", "Message"));
            }
        });
    }

    /** Downloads an asset (URL valid 12h) into output/assets so it appears in the library. */
    public JobDto download(String assetId) {
        AssetApi api = api();
        ArkClient client = ArkClient.from(config);
        Path dir = config.outputDir().resolve("assets");
        return jobs.start("download", "Tải asset " + assetId, ctx -> {
            Map<String, Object> a = api.getAsset(assetId);
            String url = Json.str(a, "URL");
            if (url == null) throw new IllegalStateException("Asset chưa có URL (trạng thái " + Json.str(a, "Status") + ")");
            String p = URI.create(url).getPath();
            String ext = p != null && p.lastIndexOf('.') > p.lastIndexOf('/') ? p.substring(p.lastIndexOf('.')) : "";
            Path target = client.download(url, dir.resolve(assetId + ext));
            GeneratedFile f = fileLibrary.record(new SavedOutput(target, null), null, Json.str(a, "Name"), null, ctx.getId());
            f.setAssetId(assetId);
            f.setAssetStatus(Json.str(a, "Status"));
            ctx.addFile(fileLibrary.save(f));
            ctx.log("Đã tải " + target);
        });
    }

    /** Called after generation when auto-push is on. Never throws – problems are logged on the job. */
    public void autoPush(List<GeneratedFile> files, JobContext ctx) {
        if (!config.autoPush() || files.isEmpty()) return;
        if (config.accessKey().isEmpty() || config.secretKey().isEmpty() || config.assetGroup().isEmpty()) {
            ctx.log("Tự đẩy Virtual Portrait: bỏ qua (chưa có AK/SK hoặc chưa chọn asset group).");
            return;
        }
        AssetApi api = api();
        for (GeneratedFile f : files) {
            try {
                pushFile(f, api, config.assetGroup(), ctx);
            } catch (Exception e) {
                ctx.log("Đẩy " + f.getName() + " lỗi: " + GlobalExceptionHandler.describe(e));
            }
        }
    }

    private boolean pushFile(GeneratedFile f, AssetApi api, String group, JobContext ctx) throws Exception {
        return pushFile(f, api, group, f.getName(), ctx::log, ctx::isCancelled);
    }

    /** @return true when the asset is Active */
    private boolean pushFile(GeneratedFile f, AssetApi api, String group, String assetName, java.util.function.Consumer<String> log,
                             java.util.function.BooleanSupplier cancelled) throws Exception {
        if (f.getAssetId() != null && !"Failed".equals(f.getAssetStatus())) {
            log.accept(f.getName() + " đã có asset://" + f.getAssetId() + " – bỏ qua");
            return "Active".equals(f.getAssetStatus());
        }
        if (f.getKind() == MediaKind.IMAGE && java.nio.file.Files.isRegularFile(fileLibrary.pathOf(f))) AssetApi.checkImage(fileLibrary.pathOf(f));
        String url = publicUrl(f, log);
        log.accept("Đẩy " + f.getName() + " vào Virtual Portrait (" + f.getKind().assetType() + ")…");
        String id = api.createAsset(group, url, f.getKind().assetType(), assetName);
        f.setAssetId(id);

        f.setAssetStatus("Processing");
        f.setAssetError(null);
        fileLibrary.save(f);

        Map<String, Object> a = api.waitActive(id, log, cancelled);
        if (a == null) return false;
        f.setAssetStatus(Json.str(a, "Status"));
        if ("Failed".equals(f.getAssetStatus())) {
            f.setAssetError(Json.str(a, "Error", "Code") + ": " + Json.str(a, "Error", "Message"));
            log.accept("  asset " + id + " THẤT BẠI: " + f.getAssetError());
        } else {
            log.accept("  → dùng làm tham chiếu: asset://" + id);
        }
        fileLibrary.save(f);
        return "Active".equals(f.getAssetStatus());
    }
}
