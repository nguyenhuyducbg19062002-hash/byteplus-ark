package ark.desktop;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

import org.springframework.boot.SpringApplication;

import ark.ArkStudioApplication;

/**
 * Chạy Ark Studio như app desktop (bản đóng gói jpackage, -Dark.desktop=true):
 * backend chỉ nghe 127.0.0.1:18080, giao diện mở trong cửa sổ riêng (Edge chế độ app), có biểu tượng ở khay hệ thống.
 * Dữ liệu: %USERPROFILE%\.byteplus-ark\{data,logs,webview} – không phụ thuộc thư mục cài đặt.
 */
public final class DesktopLauncher {
    public static final int DEFAULT_PORT = 18080;

    private DesktopLauncher() {}

    public static Path home() {
        return Path.of(System.getProperty("user.home"), ".byteplus-ark");
    }

    public static int port() {
        return Integer.getInteger("ark.port", DEFAULT_PORT);
    }

    public static String url() {
        return "http://127.0.0.1:" + port() + "/";
    }

    /** Giữ khoá suốt đời tiến trình: chỉ một Ark Studio chạy (bấm đúp icon 2 lần không mở 2 bản). */
    private static java.nio.channels.FileChannel lockChannel;
    private static java.nio.channels.FileLock instanceLock;

    public static void launch(String[] args) {
        System.setProperty("ark.desktop", "true");
        Path home = home();
        if (!acquireInstanceLock(home)) {
            // Đã có Ark Studio: chạy xong rồi → mở thêm cửa sổ; đang khởi động → nó sẽ tự mở cửa sổ, bản này chỉ thoát
            String running = runningBuild();
            if (running != null) openOrWarn(running);
            System.exit(0);
        }
        String running = runningBuild();
        if (running != null) { // bản cũ (trước khi có khoá) đang chạy
            openOrWarn(running);
            System.exit(0);
        }
        if (System.getProperty("app.data-dir") == null) {
            System.setProperty("app.data-dir", home.resolve("data").toString().replace('\\', '/'));
        }
        // System property thắng application.properties (server.port=8080 của bản dev)
        System.setProperty("server.port", String.valueOf(port()));
        System.setProperty("logging.file.name", home.resolve("logs").resolve("ark-studio.log").toString());
        SpringApplication app = new SpringApplication(ArkStudioApplication.class);
        app.setHeadless(false); // cần AWT cho khay hệ thống / hộp thoại
        try {
            app.run(args);
        } catch (Throwable e) {
            try {
                Files.createDirectories(home.resolve("logs"));
                Files.writeString(home.resolve("logs").resolve("startup-error.log"),
                        java.time.Instant.now() + "\n" + stackTrace(e));
            } catch (IOException ignored) {
                // không ghi được log thì vẫn báo bằng hộp thoại
            }
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            if (why.contains("Port") || why.contains("already in use")) {
                why = "Cổng " + port() + " đang bị chương trình khác dùng.";
            }
            JOptionPane.showMessageDialog(null, "Không khởi động được Ark Studio:\n" + why
                    + "\n\nXem log: " + home.resolve("logs").resolve("ark-studio.log"), "Ark Studio", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /** @return false nếu một Ark Studio khác đang giữ khoá (đang chạy hoặc đang khởi động) */
    static boolean acquireInstanceLock(Path home) {
        try {
            Files.createDirectories(home);
            lockChannel = java.nio.channels.FileChannel.open(home.resolve("app.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
            return instanceLock != null;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            return true; // không tạo được file khoá → vẫn cho chạy (cổng 18080 vẫn chặn bản thứ hai)
        }
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    /** Có Ark Studio (không phải chương trình khác) đang nghe ở cổng của app không. */
    static boolean isRunning() {
        return runningBuild() != null;
    }

    /**
     * Nội dung ping của Ark Studio đang chạy (null nếu không có). Bản cũ trước 1.1.0 không trả về "build".
     */
    static String runningBuild() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url() + "api/v1/app/ping"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("ark-studio") ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Đang chạy đúng bản này → mở thêm cửa sổ. Đang chạy bản KHÁC (thường là bản cũ còn chạy nền ở khay sau khi cài bản mới)
     * → báo người dùng thoát bản đó, không mở cửa sổ tới giao diện cũ.
     */
    static void openOrWarn(String pingBody) {
        if (pingBody.contains(AppVersion.build())) {
            openWindow();
            return;
        }
        JOptionPane.showMessageDialog(null,
                "Một bản Ark Studio khác (bản cũ) vẫn đang chạy nền nên chưa mở được bản " + AppVersion.version() + " vừa cài.\n\n"
                        + "Cách sửa: chuột phải biểu tượng Ark Studio ở khay hệ thống (góc phải thanh taskbar, có thể nằm trong mũi tên ^)"
                        + " › Thoát, rồi mở lại Ark Studio.\n\nClip đang tạo dở không mất: mở lại rồi bấm Chạy để theo dõi tiếp.",
                "Ark Studio", JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Mở giao diện trong cửa sổ riêng: Edge (có sẵn trên Windows) / Chrome chế độ --app với hồ sơ riêng của app.
     * Không có trình duyệt nào → mở bằng trình duyệt mặc định. Trả về tiến trình trình duyệt (null nếu không theo dõi được).
     */
    public static Process openWindow() {
        Path browser = findBrowser();
        try {
            if (browser != null) {
                List<String> cmd = new ArrayList<>(List.of(browser.toString(),
                        // ?v=<mã build>: mỗi bản cài mới là một địa chỉ mới → Edge không dùng trang cũ đã lưu từ bản trước
                        "--app=" + url() + "?v=" + java.net.URLEncoder.encode(AppVersion.build(), java.nio.charset.StandardCharsets.UTF_8),
                        "--user-data-dir=" + home().resolve("webview"),
                        "--no-first-run", "--no-default-browser-check",
                        "--disable-features=Translate,msTranslation",
                        "--window-size=1500,950"));
                return new ProcessBuilder(cmd).start();
            }
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(URI.create(url()));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Không mở được cửa sổ: " + e.getMessage() + "\nMở trình duyệt tới " + url(),
                    "Ark Studio", JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }

    static Path findBrowser() {
        List<Path> candidates = new ArrayList<>();
        for (String env : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String base = System.getenv(env);
            if (base == null) continue;
            candidates.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
            candidates.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
