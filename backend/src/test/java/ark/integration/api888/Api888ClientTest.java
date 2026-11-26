package ark.integration.api888;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ark.integration.byteplus.Json;
import ark.service.Api888Service;

/** Api888Client với server 888API giả trên máy (không gọi 888api.xin thật). */
class Api888ClientTest {
    private HttpServer server;
    private Api888Client client;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/videos", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            String p = ex.getRequestURI().getPath();
            if ("POST".equals(ex.getRequestMethod())) {
                lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                send(ex, 200, "{\"id\":\"task_1\",\"object\":\"video\",\"status\":\"queued\"}");
            } else if (p.endsWith("/content")) {
                ex.getResponseHeaders().add("Location", "/files/v.mp4");
                send(ex, 302, "");
            } else if (p.endsWith("/task_bad")) {
                ex.getResponseHeaders().add("Retry-After", "7");
                send(ex, 429, "{\"error\":{\"message\":\"slow down\",\"code\":\"rate_limit\"}}");
            } else {
                send(ex, 200, "{\"id\":\"task_1\",\"status\":\"completed\",\"progress\":100,\"url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort() + "/v1/videos/task_1/content\"}");
            }
        });
        server.createContext("/files/", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            send(ex, 200, "MP4DATA");
        });
        server.createContext("/v1/wallet/balance", ex -> send(ex, 402, "{\"message\":\"token quota is not enough\"}"));
        server.start();
        client = new Api888Client("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-test");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createPollDownloadWithBearer(@TempDir Path dir) throws Exception {
        Map<String, Object> r = client.create(Map.of("model", "m", "prompt", "x", "images", List.of("https://a/1.png")));
        assertEquals("task_1", Api888Client.taskId(r));
        assertEquals("Bearer sk-test", lastAuth.get());
        assertTrue(lastBody.get().contains("\"images\":[\"https://a/1.png\"]"));
        Map<String, Object> t = client.task("task_1");
        assertEquals("completed", Json.str(t, "status"));
        Path out = dir.resolve("v/a.mp4");
        client.download("task_1", Json.str(t, "url"), out);
        assertEquals("MP4DATA", Files.readString(out));
        assertEquals("Bearer sk-test", lastAuth.get()); // redirect cùng máy chủ vẫn gửi key
    }

    @Test
    void errorsAndQuota() {
        Api888Exception e = assertThrows(Api888Exception.class, () -> client.task("task_bad"));
        assertEquals(429, e.status());
        assertEquals(7, e.retryAfterSeconds());
        assertEquals("rate_limit", e.code());
        Api888Exception q = assertThrows(Api888Exception.class, () -> client.balance());
        assertTrue(q.quota());
        assertEquals("task_9", Api888Client.taskId(Map.of("task_id", "task_9")));
        assertNull(Api888Client.taskId(Map.of()));
    }

    @Test
    void keyNotSentToOtherHosts() {
        assertTrue(Api888Client.sameOrigin(URI.create("https://888api.xin/v1/x"), URI.create("https://888api.xin")));
        assertFalse(Api888Client.sameOrigin(URI.create("https://cdn.example.com/v.mp4"), URI.create("https://888api.xin")));
    }

    @Test
    void secondsFitModel() {
        assertEquals(12, Api888Service.seconds(Api888Service.model("seedance2.0-933-c5-fast"), 12));
        assertEquals(15, Api888Service.seconds(Api888Service.model("seedance2.0-933-c5-fast"), 22));
        assertEquals(4, Api888Service.seconds(Api888Service.model("seedance2.0-933-c5-fast"), 2));
        assertEquals(10, Api888Service.seconds(Api888Service.model("seedance2.0-933"), 8));
        assertEquals(15, Api888Service.seconds(Api888Service.model("seedance2.0-933-economy"), 5));
        assertEquals(25, Api888Service.seconds(Api888Service.model("seedance2.5-720p-lite"), 25));
        assertEquals("image_urls", Api888Service.model("sd_2.0_mini_special").imageField());
        assertEquals("custom-x", Api888Service.model("custom-x").id());
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
