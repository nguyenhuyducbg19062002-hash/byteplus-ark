package ark.integration.botsd;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ark.integration.byteplus.Json;

/**
 * Client HTTP cho API tạo video BOTSD (tài liệu "Hướng dẫn API tạo video BOTSD", 10/11/2026):
 * <pre>
 * POST /api/web/account/login        {language, username, password}            → {clientToken, balance…}
 * POST /api/web/session              {clientToken, language}                   → {balance, videoPricing…}
 * POST /api/web/create               {clientToken, prompt, model, seconds…}    → 202 {job:{id…}, balance}
 * GET  /api/web/job/{id}?clientToken                                           → {job:{status, progressPercent, failed, videoReady, message}}
 * GET  /api/web/history?clientToken                                            → {jobs:[…30 task gần nhất], balance}
 * GET  /api/web/video/{id}?clientToken&amp;download=1                            → file mp4
 * </pre>
 * Không log clientToken, mật khẩu hay base64 ảnh.
 */
public final class BotsdClient {
    public static final String DEFAULT_BASE_URL = "https://botsd.pro";

    private final HttpClient http;
    private final String baseUrl;

    public BotsdClient(String baseUrl) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim()).replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> login(String username, String password) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", "vi");
        body.put("username", username);
        body.put("password", password);
        return post("/api/web/account/login", body, Duration.ofSeconds(60));
    }

    public Map<String, Object> session(String token) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientToken", token);
        body.put("language", "vi");
        return post("/api/web/session", body, Duration.ofSeconds(60));
    }

    /** Tạo task; payload chưa có clientToken (thêm ở đây). Trả về toàn bộ phản hồi {job, balance}. */
    public Map<String, Object> create(String token, Map<String, Object> payload) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientToken", token);
        body.put("language", "vi");
        body.putAll(payload);
        return post("/api/web/create", body, Duration.ofMinutes(3));
    }

    public Map<String, Object> job(String token, String jobId) throws IOException, InterruptedException {
        return get("/api/web/job/" + enc(jobId) + "?clientToken=" + enc(token), Duration.ofSeconds(30));
    }

    public List<?> history(String token) throws IOException, InterruptedException {
        return Json.list(get("/api/web/history?clientToken=" + enc(token), Duration.ofSeconds(30)), "jobs");
    }

    /** Tải video đã xong (chỉ gọi khi videoReady=true) vào target (ghi file tạm rồi đổi tên). */
    public void download(String token, String jobId, Path target) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/web/video/" + enc(jobId)
                        + "?clientToken=" + enc(token) + "&download=1"))
                .timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            String text;
            try (InputStream in = res.body()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw error(res.statusCode(), text);
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = res.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(tmp) == 0) {
            Files.deleteIfExists(tmp);
            throw new IOException("BOTSD trả về file video rỗng");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------- HTTP ----------

    private Map<String, Object> post(String path, Map<String, Object> body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return handle(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> get(String pathAndQuery, Duration timeout) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(timeout).GET().build();
        return handle(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handle(HttpResponse<String> res) throws BotsdException {
        if (res.statusCode() / 100 != 2) throw error(res.statusCode(), res.body());
        try {
            Object v = Json.parse(res.body() == null || res.body().isBlank() ? "{}" : res.body());
            return v instanceof Map ? (Map<String, Object>) v : Map.of("data", v);
        } catch (IllegalArgumentException e) {
            throw new BotsdException(res.statusCode(), "Phản hồi BOTSD không phải JSON: " + cut(res.body()), null);
        }
    }

    static BotsdException error(int status, String body) {
        String msg = null;
        Integer retry = null;
        try {
            Object v = Json.parse(body == null || body.isBlank() ? "{}" : body);
            for (String k : new String[] {"message", "error", "detail", "msg"}) {
                Object m = Json.path(v, k);
                if (m instanceof String s && !s.isBlank()) {
                    msg = s;
                    break;
                }
                if (m instanceof Map<?, ?> mm && mm.get("message") instanceof String s2) {
                    msg = s2;
                    break;
                }
            }
            Long r = Json.lng(v, "retryAfterSeconds");
            if (r != null) retry = r.intValue();
        } catch (IllegalArgumentException ignored) {
            msg = cut(body);
        }
        if (msg == null || msg.isBlank()) msg = defaultMessage(status);
        return new BotsdException(status, msg, retry);
    }

    static String defaultMessage(int status) {
        return switch (status) {
            case 400 -> "Prompt, model, thời lượng hoặc tệp không hợp lệ";
            case 401 -> "Phiên đăng nhập BOTSD không hợp lệ / sai tài khoản";
            case 402 -> "Không đủ Point";
            case 404 -> "Không tìm thấy task / video";
            case 409 -> "Đã đạt giới hạn task đang chạy";
            case 410 -> "Dữ liệu tham chiếu / kết quả đã hết hạn";
            case 429 -> "Gửi quá nhanh";
            case 503 -> "BOTSD đang bảo trì hoặc tạm hết tài nguyên";
            default -> "Lỗi HTTP " + status;
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String cut(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
