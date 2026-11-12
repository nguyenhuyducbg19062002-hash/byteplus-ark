package ark;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Backend REST API (/api/v1): Seedream (ảnh), Seedance (video), Virtual Portrait (asset). */
@SpringBootApplication
@EnableScheduling
public class ArkStudioApplication {
    public static void main(String[] args) {
        // Bản app desktop (jpackage truyền -Dark.desktop=true): cửa sổ riêng + khay hệ thống, dữ liệu ở %USERPROFILE%\.byteplus-ark
        if (Boolean.getBoolean("ark.desktop") || java.util.Arrays.asList(args).contains("--desktop")) {
            ark.desktop.DesktopLauncher.launch(args);
            return;
        }
        // H2 database lives in <project>/data whether started from the project root or from backend/
        if (System.getProperty("app.data-dir") == null) {
            System.setProperty("app.data-dir", projectRoot().resolve("data").toString().replace('\\', '/'));
        }
        SpringApplication.run(ArkStudioApplication.class, args);
    }

    public static Path projectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return cwd.getFileName() != null && cwd.getFileName().toString().equalsIgnoreCase("backend") ? cwd.getParent() : cwd;
    }
}