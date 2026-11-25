package ark.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.integration.botsd.BotsdClient;
import ark.integration.botsd.BotsdException;
import ark.integration.byteplus.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Giữ phiên BOTSD: clientToken lưu trong config.properties (không vào DB / giao diện). Gặp 401 thì tự đăng nhập lại
 * bằng tài khoản đã lưu rồi gọi lại đúng một lần.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotsdService {
    /** Model BOTSD: mã → [tên, các thời lượng hợp lệ]. */
    public record BotsdModel(String id, String name, List<Integer> seconds, List<Integer> basePoints, int maxImages) {}

    public static final List<BotsdModel> MODELS = List.of(
            new BotsdModel("sd20", "Seedance 2.0", List.of(5, 10, 15), List.of(2, 6, 8), 30),
            new BotsdModel("sd25", "Seedance 2.5", List.of(5, 10, 15, 30), List.of(9, 18, 25, 50), 30),
            new BotsdModel("sd25hd", "Seedance 2.5 1080P", List.of(5, 10, 15, 30), List.of(12, 24, 35, 70), 30),
            new BotsdModel("muse", "Muse AI", List.of(5, 10), List.of(2, 4), 1));

    public static final List<String> RATIOS = List.of("16:9", "9:16", "1:1", "4:3", "3:4", "21:9");

    public static BotsdModel model(String id) {
        return MODELS.stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Model BOTSD không hợp lệ: " + id));
    }

    /** Thời lượng clip → giá trị BOTSD nhận (5/10/15/30): mức nhỏ nhất ≥ thời lượng clip, quá dài thì lấy mức lớn nhất. */
    public static int seconds(BotsdModel m, int clipSeconds) {
        for (int s : m.seconds()) if (s >= clipSeconds) return s;
        return m.seconds().get(m.seconds().size() - 1);
    }

    @FunctionalInterface
    public interface Call<T> {
        T run(BotsdClient client, String token) throws IOException, InterruptedException;
    }

    private final Config config;

    public boolean configured() {
        return !config.botsdToken().isEmpty() || (!config.botsdUsername().isEmpty() && !config.botsdPassword().isEmpty());
    }

    public BotsdClient client() {
        return new BotsdClient(config.botsdBaseUrl());
    }

    /** Đăng nhập bằng tài khoản (đã lưu nếu tham số null) và lưu clientToken. */
    public synchronized Map<String, Object> login(String username, String password) throws IOException, InterruptedException {
        String u = username != null && !username.isBlank() ? username.trim() : config.botsdUsername();
        String p = password != null && !password.isEmpty() ? password : config.botsdPassword();
        if (u.isEmpty() || p.isEmpty()) throw new IllegalStateException("Chưa nhập tài khoản BOTSD – vào Cài đặt › BOTSD");
        Map<String, Object> r = client().login(u, p);
        String token = Json.str(r, "clientToken");
        if (token == null || token.isBlank()) throw new IOException("BOTSD không trả về clientToken");
        config.setBotsdUsername(u);
        config.setBotsdPassword(p);
        config.setBotsdToken(token);
        config.save();
        log.info("Đã đăng nhập BOTSD tài khoản {}", u);
        return r;
    }

    /** Gọi API với token hiện có; 401 → đăng nhập lại một lần rồi gọi lại. */
    public <T> T call(Call<T> fn) throws IOException, InterruptedException {
        String token = config.botsdToken();
        if (token.isEmpty()) {
            login(null, null);
            token = config.botsdToken();
        }
        try {
            return fn.run(client(), token);
        } catch (BotsdException e) {
            if (e.status() != 401 || config.botsdPassword().isEmpty()) throw e;
            synchronized (this) {
                if (token.equals(config.botsdToken())) login(null, null); // luồng khác chưa đăng nhập lại
            }
            return fn.run(client(), config.botsdToken());
        }
    }

    /** Số dư + bảng giá hiện hành (cho trang Cài đặt / Cấu hình chạy). */
    public Map<String, Object> status() throws IOException, InterruptedException {
        Map<String, Object> s = call((c, t) -> c.session(t));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", config.botsdUsername());
        out.put("balance", Json.path(s, "balance"));
        out.put("videoPricing", Json.path(s, "videoPricing"));
        return out;
    }

    public Map<String, Object> create(Map<String, Object> payload) throws IOException, InterruptedException {
        return call((c, t) -> c.create(t, payload));
    }

    public Map<String, Object> job(String id) throws IOException, InterruptedException {
        return call((c, t) -> c.job(t, id));
    }

    public List<?> history() throws IOException, InterruptedException {
        return call((c, t) -> c.history(t));
    }

    public void download(String id, Path target) throws IOException, InterruptedException {
        call((c, t) -> {
            c.download(t, id, target);
            return null;
        });
    }

    public void logout() throws IOException {
        config.setBotsdToken("");
        config.setBotsdPassword("");
        config.save();
    }
}
