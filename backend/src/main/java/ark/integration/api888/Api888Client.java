package ark.integration.api888;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import ark.integration.byteplus.Json;

/**
 * Client HTTP cho API video của 888API (https://888api.xin/docs), xác thực "Authorization: Bearer sk-…":
 * <pre>
 * POST /v1/videos                {model, prompt, duration, seconds, ratio, resolution, images:[url…]} → {id, status:"queued"}
 * GET  /v1/videos/{id}           → {id, status: queued|in_progress|completed|failed, progress, url, error|message}
 * GET  /v1/videos/{id}/content   → file mp4 (có thể 302 sang nơi khác – chỉ gửi kèm key khi cùng máy chủ)
 * GET  /v1/wallet/balance        → {success, data:{currency, available_balance…}}
 * GET  /v1/models                → {data:[{id}…]}
 * </pre>
 * Ảnh tham chiếu phải là link HTTPS công khai (không nhận base64). Không log API key.
 */
public final class Api888Client {
    public static final String DEFAULT_BASE_URL = "https://888api.xin";

    private final HttpClient http;
    private final String baseUrl;
    private final String key;

    public Api888Client(String baseUrl, String key) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim()).replaceAll("/+$", "").replaceAll("/v1$", "");
        this.key = Objects.requireNonNull(key, "key");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER) // tự theo redirect để không gửi key sang máy chủ khác
                .build();
    }

    /** Tạo task (không tự gửi lại). Model có bước tải ảnh về máy chủ 888API có thể mất tới 5 phút mới trả lời. */
    public Map<String, Object> create(Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/videos")))
                .timeout(Duration.ofMinutes(6))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return handle(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    /** Mã task trong phản hồi tạo: id, một số phản hồi dùng task_id. */
    public static String taskId(Map<String, Object> created) {
        String id = Json.str(created, "id");
        if (id == null || id.isBlank()) id = Json.str(created, "task_id");
        if (id == null || id.isBlank()) id = Json.str(created, "data", "id");
        if (id == null || id.isBlank()) id = Json.str(created, "data", "task_id");
        return id == null || id.isBlank() ? null : id;
    }

    public Map<String, Object> task(String id) throws IOException, InterruptedException {
        return get("/v1/videos/" + enc(id), Duration.ofSeconds(60));
    }

    public Map<String, Object> balance() throws IOException, InterruptedException {
        return get("/v1/wallet/balance", Duration.ofSeconds(30));
    }

    public Map<String, Object> models() throws IOException, InterruptedException {
        return get("/v1/models", Duration.ofSeconds(30));
    }

    /**
     * Tải video đã xong vào target (ghi file tạm rồi đổi tên). url = link trong phản hồi task (null → /v1/videos/{id}/content).
     * Theo tối đa 5 redirect; sang máy chủ khác thì bỏ key.
     */
    public void download(String id, String url, Path target) throws IOException, InterruptedException {
        URI uri = URI.create(url != null && !url.isBlank() ? url : baseUrl + "/v1/videos/" + enc(id) + "/content");
        URI home = URI.create(baseUrl);
        HttpResponse<InputStream> res = null;
        for (int hop = 0; hop <= 5; hop++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).GET();
            if (sameOrigin(uri, home)) auth(b);
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            int s = res.statusCode();
            if (s / 100 == 3 && res.headers().firstValue("Location").isPresent()) {
                res.body().close();
                uri = uri.resolve(res.headers().firstValue("Location").get());
                continue;
            }
            break;
        }
        if (res.statusCode() / 100 != 2) {
            String text;
            try (InputStream in = res.body()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw error(res.statusCode(), text, null);
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = res.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(tmp) == 0) {
            Files.deleteIfExists(tmp);
            throw new IOException("888API trả về file video rỗng");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean sameOrigin(URI a, URI b) {
        return Objects.equals(lower(a.getScheme()), lower(b.getScheme())) && Objects.equals(lower(a.getHost()), lower(b.getHost()))
                && port(a) == port(b);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    private static int port(URI u) {
        return u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    // ---------- HTTP ----------

    private HttpRequest.Builder auth(HttpRequest.Builder b) {
        return b.header("Authorization", "Bearer " + key);
    }

    private Map<String, Object> get(String path, Duration timeout) throws IOException, InterruptedException {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + path))).timeout(timeout).GET().build();
        return handle(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handle(HttpResponse<String> res) throws Api888Exception {
        if (res.statusCode() / 100 != 2) {
            Integer retry = res.headers().firstValue("Retry-After").map(v -> {
                try {
                    return Integer.parseInt(v.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }).orElse(null);
            throw error(res.statusCode(), res.body(), retry);
        }
        try {
            Object v = Json.parse(res.body() == null || res.body().isBlank() ? "{}" : res.body());
            return v instanceof Map ? (Map<String, Object>) v : Map.of("data", v);
        } catch (IllegalArgumentException e) {
            throw new Api888Exception(res.statusCode(), null, "Phản hồi 888API không phải JSON: " + cut(res.body()), null);
        }
    }

    /** Lỗi không có mẫu JSON cố định trong tài liệu: thử error.message / error.code / message / msg / detail, không có thì lấy nguyên văn. */
    static Api888Exception error(int status, String body, Integer retryAfter) {
        String msg = null;
        String code = null;
        try {
            Object v = Json.parse(body == null || body.isBlank() ? "{}" : body);
            Object err = Json.path(v, "error");
            if (err instanceof Map) {
                msg = Json.str(err, "message");
                code = Json.str(err, "code");
                if (code == null) code = Json.str(err, "type");
            } else if (err instanceof String s && !s.isBlank()) {
                code = s.matches("[A-Za-z_]+") ? s : null;
                msg = s;
            }
            for (String k : new String[] {"message", "msg", "detail"}) {
                if (msg != null && !msg.isBlank()) break;
                Object m = Json.path(v, k);
                if (m instanceof String s && !s.isBlank()) msg = s;
            }
            if (code == null) code = Json.str(v, "code");
            if (retryAfter == null) {
                Long r = Json.lng(v, "retry_after");
                if (r != null) retryAfter = r.intValue();
            }
        } catch (IllegalArgumentException | ClassCastException ignored) {
            msg = cut(body);
        }
        if (msg == null || msg.isBlank()) msg = defaultMessage(status);
        if (code != null && !msg.contains(code)) msg = code + " – " + msg;
        return new Api888Exception(status, code, msg, retryAfter);
    }

    static String defaultMessage(int status) {
        return switch (status) {
            case 400, 422 -> "Tham số không hợp lệ (model, thời lượng, tỉ lệ, prompt hoặc link ảnh)";
            case 401 -> "API key 888API sai hoặc đã bị xoá";
            case 403 -> "Key không có quyền dùng model này";
            case 402 -> "Không đủ số dư / hạn mức key";
            case 404 -> "Không tìm thấy task";
            case 410 -> "Task / kết quả đã hết hạn";
            case 429 -> "Gửi quá nhanh";
            default -> "Lỗi HTTP " + status;
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String cut(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
