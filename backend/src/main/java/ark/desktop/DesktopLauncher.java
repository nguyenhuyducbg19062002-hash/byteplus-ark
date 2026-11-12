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

    public static void launch(String[] args) {
        System.setProperty("ark.desktop", "true");
        // Đã có một Ark Studio đang chạy → chỉ mở thêm cửa sổ rồi thoát
        if (isRunning()) {
            openWindow();
            System.exit(0);
        }
        Path home = home();
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

    private static String stackTrace(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    /** Có Ark Studio (không phải chương trình khác) đang nghe ở cổng của app không. */
    static boolean isRunning() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url() + "api/v1/app/ping"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("ark-studio");
        } catch (Exception e) {
            return false;
        }
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
                        "--app=" + url(),
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
