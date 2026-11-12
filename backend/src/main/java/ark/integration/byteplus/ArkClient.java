package ark.integration.byteplus;

import ark.config.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin HTTP client for the BytePlus ModelArk data-plane API.
 * Docs: docs/api/*.md (copied from docs.byteplus.com/en/docs/ModelArk).
 */
public final class ArkClient {
    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public ArkClient(String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Chưa có API key. Nhập trong tab Cài đặt hoặc đặt biến môi trường ARK_API_KEY.");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static ArkClient from(Config c) {
        return new ArkClient(c.baseUrl(), c.apiKey());
    }

    // ---------- Image generation (Seedream) ----------

    /** POST /images/generations (synchronous; can take a while for 4K / sequential output). */
    public Map<String, Object> generateImages(Map<String, Object> body) throws IOException, InterruptedException {
        return send("POST", "/images/generations", body, Duration.ofMinutes(10));
    }

    // ---------- Chat (model ngôn ngữ Dola-Seed) ----------

    /** POST /chat/completions (đồng bộ). */
    public Map<String, Object> chat(Map<String, Object> body) throws IOException, InterruptedException {
        return send("POST", "/chat/completions", body, Duration.ofMinutes(3));
    }

    // ---------- Video generation (Seedance) ----------

    /** POST /contents/generations/tasks — returns {"id": "..."}. */
    public Map<String, Object> createVideoTask(Map<String, Object> body) throws IOException, InterruptedException {
        return send("POST", "/contents/generations/tasks", body, Duration.ofMinutes(3));
    }

    /** GET /contents/generations/tasks/{id}. */
    public Map<String, Object> getVideoTask(String id) throws IOException, InterruptedException {
        return send("GET", "/contents/generations/tasks/" + enc(id), null, Duration.ofMinutes(1));
    }

    /** GET /contents/generations/tasks?page_num=&page_size=&filter.status=&filter.model= */
    public Map<String, Object> listVideoTasks(int pageNum, int pageSize, String status, String model)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder("/contents/generations/tasks?page_num=").append(pageNum)
                .append("&page_size=").append(pageSize);
        if (status != null && !status.isBlank()) q.append("&filter.status=").append(enc(status));
        if (model != null && !model.isBlank()) q.append("&filter.model=").append(enc(model));
        return send("GET", q.toString(), null, Duration.ofMinutes(1));
    }

    /** DELETE /contents/generations/tasks/{id} — cancels a queued task or deletes a finished task record. */
    public void deleteVideoTask(String id) throws IOException, InterruptedException {
        send("DELETE", "/contents/generations/tasks/" + enc(id), null, Duration.ofMinutes(1));
    }

    // ---------- Download outputs ----------

    /** Downloads a generated file (output URLs are pre-signed, valid 24h; no auth header needed). */
    public Path download(String url, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15)).GET().build();
        HttpResponse<Path> r = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
        if (r.statusCode() >= 400) {
            Files.deleteIfExists(tmp);
            throw new IOException("Tải file lỗi HTTP " + r.statusCode() + " (URL có thể đã hết hạn 24h)");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    // ---------- internals ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String method, String path, Map<String, Object> body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = r.body() == null ? "" : r.body();
        Object parsed = null;
        if (!text.isBlank()) {
            try {
                parsed = Json.parse(text);
            } catch (IllegalArgumentException ignored) {
                // non-JSON body; handled below
            }
        }
        if (r.statusCode() >= 400) {
            String code = Json.str(parsed, "error", "code");
            String msg = Json.str(parsed, "error", "message");
            if (msg == null) msg = text.isBlank() ? "HTTP " + r.statusCode() : truncate(text, 500);
            String reqId = r.headers().firstValue("x-request-id")
                    .or(() -> r.headers().firstValue("x-tt-logid")).orElse(null);
            throw new ArkException(r.statusCode(), code, msg, reqId);
        }
        return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
