package ark.integration.botsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import ark.service.BotsdService;

/** BotsdClient với một server BOTSD giả trên máy (không gọi botsd.pro thật). */
class BotsdClientTest {
    private HttpServer server;
    private BotsdClient client;
    private final AtomicReference<String> lastCreateBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/web/account/login", ex -> {
            String body = read(ex);
            if (body.contains("\"password\":\"sai\"")) send(ex, 401, "{\"message\":\"Sai tài khoản hoặc mật khẩu\"}");
            else send(ex, 200, "{\"clientToken\":\"tok1\",\"balance\":100}");
        });
        server.createContext("/api/web/create", ex -> {
            lastCreateBody.set(read(ex));
            send(ex, 202, "{\"job\":{\"id\":\"web_1\",\"status\":\"preparing\",\"cost\":18,\"failed\":false,\"videoReady\":false},\"balance\":82}");
        });
        server.createContext("/api/web/job/", ex -> send(ex, 200,
                "{\"job\":{\"id\":\"web_1\",\"status\":\"done\",\"progressPercent\":100,\"failed\":false,\"videoReady\":true}}"));
        server.createContext("/api/web/history", ex -> send(ex, 429, "{\"message\":\"Chậm lại\",\"retryAfterSeconds\":12}"));
        server.createContext("/api/web/video/", ex -> {
            assertTrue(ex.getRequestURI().getQuery().contains("download=1"));
            send(ex, 200, "MP4DATA");
        });
        server.start();
        client = new BotsdClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void loginCreatePollDownload(@TempDir Path dir) throws Exception {
        assertEquals("tok1", Json.str(client.login("u", "p"), "clientToken"));
        Map<String, Object> r = client.create("tok1", Map.of("prompt", "x", "model", "sd25", "seconds", 10, "images", List.of()));
        assertEquals("web_1", Json.str(r, "job", "id"));
        assertTrue(lastCreateBody.get().contains("\"clientToken\":\"tok1\""));
        assertEquals(Boolean.TRUE, Json.path(client.job("tok1", "web_1"), "job", "videoReady"));
        Path out = dir.resolve("v/a.mp4");
        client.download("tok1", "web_1", out);
        assertEquals("MP4DATA", Files.readString(out));
    }

    @Test
    void errorsCarryStatusMessageAndRetryAfter() {
        BotsdException e = assertThrows(BotsdException.class, () -> client.login("u", "sai"));
        assertEquals(401, e.status());
        assertEquals("Sai tài khoản hoặc mật khẩu", e.getMessage());
        BotsdException h = assertThrows(BotsdException.class, () -> client.history("tok1"));
        assertEquals(429, h.status());
        assertEquals(12, h.retryAfterSeconds());
        assertEquals("Không đủ Point", BotsdClient.error(402, "").getMessage());
    }

    @Test
    void secondsRoundUpToAllowedValues() {
        BotsdService.BotsdModel sd25 = BotsdService.model("sd25");
        assertEquals(5, BotsdService.seconds(sd25, 4));
        assertEquals(10, BotsdService.seconds(sd25, 9));
        assertEquals(15, BotsdService.seconds(sd25, 12));
        assertEquals(30, BotsdService.seconds(sd25, 22));
        assertEquals(10, BotsdService.seconds(BotsdService.model("muse"), 15));
    }

    private static String read(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
