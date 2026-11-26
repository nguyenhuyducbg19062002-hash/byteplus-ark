package ark.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.entity.GeneratedFile;
import ark.integration.api888.Api888Client;
import ark.integration.byteplus.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 888API (888api.xin): API key "sk-…" lưu trong config.properties. Ảnh tham chiếu phải là link HTTPS công khai nên ảnh
 * trên máy được tải lên nơi chứa tạm (TOS riêng tư của BytePlus – link có chữ ký 48 giờ, hoặc Litterbox 72 giờ),
 * đặt theo thư mục của từng dự án – không đẩy vào thư viện Virtual Portrait.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Api888Service {
    /**
     * Model video 888API dùng cho kịch bản (theo bảng giá trong tài liệu 888api.xin/docs). durations = các mức cố định
     * (null = mọi giá trị từ min tới max); promptMax 0 = không rõ (để máy chủ kiểm tra).
     */
    public record Model(String id, String name, int min, int max, List<Integer> durations, String resolution,
                        List<String> ratios, int maxImages, int promptMax, String imageField, String note) {}

    static final List<String> SIX = List.of("16:9", "9:16", "4:3", "3:4", "1:1", "21:9");
    static final List<String> THREE = List.of("16:9", "9:16", "1:1");
    static final List<String> FOUR = List.of("16:9", "9:16", "1:1", "21:9");

    public static final List<Model> MODELS = List.of(
            new Model("seedance2.0-933-c5-fast", "Seedance 2.0 C5 Fast", 4, 15, null, "720p", SIX, 9, 8000, "images", "2.8 元/条"),
            new Model("seedance2.0-933-c8-full", "Seedance 2.0 C8 Full", 4, 15, null, "720p", SIX, 9, 8000, "images", "4.3 元/条"),
            new Model("seedance2.0-933-c4-mini", "Seedance 2.0 C4 Mini", 4, 15, null, "720p", THREE, 9, 0, "images", "2 元/条 · nhận người thật"),
            new Model("seedance2.0-933", "Seedance 2.0 (933)", 10, 15, List.of(10, 15), "720p", SIX, 9, 0, "images", "5 元/条 · nhận người thật"),
            new Model("seedance2.0-933-economy", "Seedance 2.0 Economy", 15, 15, List.of(15), "720p",
                    List.of("16:9", "9:16", "1:1", "4:3", "3:4"), 9, 0, "images", "5 元/条 · 15s · nhận người thật"),
            new Model("seedance2.0-fast-720p", "Seedance 2.0 Fast 720p", 4, 15, null, "720p", SIX, 30, 0, "images", "1.5 元/条 · mặt người gốc"),
            new Model("seedance2.0满血-720p", "Seedance 2.0 Full 720p", 4, 15, null, "720p", SIX, 30, 0, "images", "3.5 元/条 · mặt người gốc"),
            new Model("sd_2.0_mini_special", "Seedance 2.0 Mini Special", 4, 15, null, "720p", THREE, 9, 5000, "image_urls", "0.15 元/秒"),
            new Model("seedance2.5-720p-lite", "Seedance 2.5 Lite 720p", 4, 30, null, "720p", SIX, 30, 10000, "images", "3.5 元/条"),
            new Model("seedance2.5-720p-promo", "Seedance 2.5 Promo 720p", 4, 30, null, "720p", SIX, 30, 0, "images", "4 元/条"),
            new Model("seedance2.5-flash", "Seedance 2.5 Flash", 4, 30, null, "720p", SIX, 10, 0, "images", "4.5 元/条 · mặt người gốc"),
            new Model("seedance2.5-930-1080p", "Seedance 2.5 1080p", 5, 30, null, "1080p", SIX, 9, 0, "images", "5.3 元/条 · mặt người gốc"),
            new Model("sd2.5-720p-full", "Seedance 2.5 Full 720p", 5, 30, null, "720p", THREE, 30, 0, "images", "6.3 元/条"),
            new Model("seedance2.5-301010-full", "Seedance 2.5 301010 Full", 5, 30, null, "720p", SIX, 30, 45000, "images", "6.5 元/条"),
            new Model("seedance2.5-301010-face", "Seedance 2.5 Face", 5, 30, null, "720p", FOUR, 30, 10000, "images",
                    "7.5 元/条 · dành cho mặt người, chỉ PNG/JPEG ≤10MB"));

    public static final String DEFAULT_MODEL = "seedance2.0-933-c5-fast";

    /** Model trong danh sách, hoặc model tự nhập (giới hạn chung: 4–15s, 6 tỉ lệ, 9 ảnh, trường images). */
    public static Model model(String id) {
        String m = id == null || id.isBlank() ? DEFAULT_MODEL : id.trim();
        return MODELS.stream().filter(x -> x.id().equals(m)).findFirst()
                .orElse(new Model(m, m, 4, 15, null, "720p", SIX, 9, 0, "images", "model tự nhập"));
    }

    /** Thời lượng clip → giá trị model nhận: mức cố định nhỏ nhất ≥ clip (không có thì lớn nhất), hoặc kẹp trong [min, max]. */
    public static int seconds(Model m, int clip) {
        if (m.durations() != null && !m.durations().isEmpty()) {
            for (int s : m.durations()) if (s >= clip) return s;
            return m.durations().get(m.durations().size() - 1);
        }
        return Math.max(m.min(), Math.min(m.max(), clip));
    }

    private final Config config;
    private final AssetLibraryService assetLibrary;
    private final FileLibraryService fileLibrary;

    public boolean configured() {
        return !config.api888Key().isEmpty();
    }

    public Api888Client client() {
        if (!configured()) throw new IllegalStateException("Chưa nhập API key 888API – vào Cài đặt › 888API");
        return new Api888Client(config.api888BaseUrl(), config.api888Key());
    }

    public void save(String key, String imageHost) throws IOException {
        if (key != null && !key.isBlank() && !key.contains("…")) config.setApi888Key(key.trim());
        if (imageHost != null) {
            if (!imageHost.equals("TOS") && !imageHost.equals("LITTERBOX")) throw new IllegalArgumentException("Nơi để ảnh không hợp lệ");
            config.setApi888ImageHost(imageHost);
        }
        config.save();
        urls.clear();
    }

    public void clear() throws IOException {
        config.setApi888Key("");
        config.save();
    }

    /** Số dư + các model video key nhìn thấy (để trang Cài đặt kiểm tra key). */
    public Map<String, Object> status() throws IOException, InterruptedException {
        Api888Client c = client();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> b = c.balance();
        Object d = Json.path(b, "data");
        out.put("currency", Json.str(d, "currency"));
        out.put("balance", Json.path(d, "available_balance") != null ? Json.path(d, "available_balance") : Json.path(d, "wallet_balance"));
        out.put("keyRemaining", Json.path(d, "key_remaining"));
        out.put("keyUnlimited", Json.path(d, "key_unlimited"));
        try {
            List<String> ids = new ArrayList<>();
            for (Object m : Json.list(c.models(), "data")) {
                String id = Json.str(m, "id");
                if (id != null) ids.add(id);
            }
            out.put("models", ids);
        } catch (Exception e) {
            out.put("models", List.of());
        }
        out.put("imageHost", config.api888ImageHost());
        return out;
    }

    // ---------- link ảnh tham chiếu ----------

    private record HostedUrl(String url, Instant expires) {}

    /** fileId|host → link đã tải lên (dùng lại cho các clip khác của dự án khi còn trên 12 giờ). */
    private final Map<String, HostedUrl> urls = new ConcurrentHashMap<>();
    private final Map<Long, Object> uploadLocks = new ConcurrentHashMap<>();

    /** Bỏ link đã tải lên của ảnh này (888API không đọc được) → lần gửi sau tải lại, lấy link mới. */
    public void forgetUrl(Long fileId) {
        urls.keySet().removeIf(k -> k.startsWith(fileId + "|"));
    }

    public String imageHost() {
        return config.api888ImageHost();
    }

    /** Link HTTPS cho ảnh của dự án (thư mục tạm theo folder dự án), tải lên một lần dù nhiều clip cùng dùng. */
    public String imageUrl(GeneratedFile f, String projectFolder, Consumer<String> say) throws Exception {
        String host = config.api888ImageHost();
        String cacheKey = f.getId() + "|" + host + "|" + projectFolder;
        synchronized (uploadLocks.computeIfAbsent(f.getId(), k -> new Object())) {
            HostedUrl h = urls.get(cacheKey);
            if (h != null && h.expires().isAfter(Instant.now().plus(Duration.ofHours(12)))) return h.url();
            String url;
            Instant expires;
            if ("LITTERBOX".equals(host)) {
                say.accept("Tải " + f.getName() + " lên Litterbox (link tạm 72 giờ)…");
                url = litterbox(fileLibrary.pathOf(f));
                expires = Instant.now().plus(Duration.ofHours(72));
            } else {
                String folder = projectFolder == null || projectFolder.isBlank() ? "khac" : projectFolder.replaceAll("[^A-Za-z0-9._-]", "_");
                url = assetLibrary.tosUrl(f, "ark-studio/888/" + folder, Duration.ofHours(48), say);
                expires = Instant.now().plus(Duration.ofHours(48));
            }
            urls.put(cacheKey, new HostedUrl(url, expires));
            return url;
        }
    }

    /** Litterbox (litterbox.catbox.moe): ai có link đều xem được, tự xoá sau 72 giờ. Chỉ dùng khi người dùng chọn. */
    static String litterbox(Path p) throws IOException, InterruptedException {
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException(p.getFileName() + " không còn trên máy");
        String boundary = "----ark" + UUID.randomUUID().toString().replace("-", "");
        String name = p.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        byte[] file = Files.readAllBytes(p);
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"time\"\r\n\r\n72h\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[h.length + file.length + tail.length];
        System.arraycopy(h, 0, body, 0, h.length);
        System.arraycopy(file, 0, body, h.length, file.length);
        System.arraycopy(tail, 0, body, h.length + file.length, tail.length);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://litterbox.catbox.moe/resources/internals/api.php"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = res.body() == null ? "" : res.body().trim();
        if (res.statusCode() / 100 != 2 || !text.startsWith("https://")) {
            throw new IOException("Litterbox không nhận ảnh (HTTP " + res.statusCode() + "): " + (text.length() > 200 ? text.substring(0, 200) : text));
        }
        return text;
    }

    /** Lý do chưa chạy được 888API (null = sẵn sàng). */
    /**
     * Kiểm tra nơi để ảnh của 888API ngay bây giờ (TOS: thử bucket thật). ok=false kèm lý do + cách sửa.
     */
    public Map<String, Object> checkImageHost() {
        return checkImageHost(false);
    }

    public Map<String, Object> checkImageHost(boolean fresh) {
        String host = config.api888ImageHost();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("host", host);
        String why = null;
        if ("TOS".equals(host)) {
            String tos = assetLibrary.probeTos(fresh);
            if (tos != null) why = "888API cần link ảnh mà " + lower(tos) + " – hoặc đổi “Nơi để ảnh” sang Litterbox ở Cài đặt › 888API (link công khai, tự xoá sau 72 giờ)";
        }
        out.put("ok", why == null);
        if (why != null) out.put("message", why);
        return out;
    }

    /** Chạy dự án 888API: báo lỗi nơi để ảnh trước khi gửi clip (không để tới lúc chạy rồi mới dừng dự án). */
    public void requireImageHost() {
        Map<String, Object> r = checkImageHost();
        if (!Boolean.TRUE.equals(r.get("ok"))) throw new IllegalStateException(String.valueOf(r.get("message")));
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public String notReady() {
        if (!configured()) return "Chưa nhập API key 888API – vào Cài đặt › 888API";
        if ("TOS".equals(config.api888ImageHost())) {
            if (!assetLibrary.hasKeys()) {
                return "888API cần link ảnh: nhập AK/SK BytePlus (Cài đặt) để app tải ảnh lên TOS, hoặc chọn nơi để ảnh “Litterbox” trong Cài đặt › 888API";
            }
            String tos = assetLibrary.tosUnavailable();
            if (tos != null) return "888API cần link ảnh mà " + lower(tos) + " – hoặc đổi “Nơi để ảnh” sang Litterbox ở Cài đặt › 888API (link công khai, tự xoá sau 72 giờ)";
        }
        return null;
    }
}
