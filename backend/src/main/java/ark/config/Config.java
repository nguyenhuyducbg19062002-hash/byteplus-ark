package ark.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.springframework.stereotype.Component;

/**
 * Settings stored in %USERPROFILE%\.byteplus-ark\config.properties (outside the project,
 * so the API key never ends up in source control). ARK_API_KEY env var is used as fallback.
 */
@Component
public class Config {
    public static final String DEFAULT_BASE_URL = "https://ark.ap-southeast.bytepluses.com/api/v3";

    private final Path file;
    private final Properties props = new Properties();

    public Config() {
        file = Path.of(System.getProperty("user.home"), ".byteplus-ark", "config.properties");
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                System.err.println("Khong doc duoc config: " + e.getMessage());
            }
        }
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(w, "BytePlus ModelArk settings");
        }
    }

    public Path file() { return file; }

    public String apiKey() {
        String k = props.getProperty("api_key", "").trim();
        if (k.isEmpty()) {
            String env = System.getenv("ARK_API_KEY");
            if (env != null) k = env.trim();
        }
        return k;
    }

    public String storedApiKey() { return props.getProperty("api_key", ""); }

    public void setApiKey(String key) { props.setProperty("api_key", key == null ? "" : key.trim()); }

    public String baseUrl() {
        String u = props.getProperty("base_url", "").trim();
        return u.isEmpty() ? DEFAULT_BASE_URL : u;
    }

    public void setBaseUrl(String url) { props.setProperty("base_url", url == null ? "" : url.trim()); }

    public Path outputDir() {
        String d = props.getProperty("output_dir", "").trim();
        if (!d.isEmpty()) return Path.of(d);
        // Bản app desktop: thư mục cài đặt không ghi được → mặc định Documents\BytePlus Ark Studio
        if (Boolean.getBoolean("ark.desktop")) return Path.of(System.getProperty("user.home"), "Documents", "BytePlus Ark Studio");
        // Chạy từ thư mục backend\ → lưu vào <project>\output
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path base = cwd.getFileName() != null && cwd.getFileName().toString().equalsIgnoreCase("backend") ? cwd.getParent() : cwd;
        return base.resolve("output");
    }

    public void setOutputDir(String dir) { props.setProperty("output_dir", dir == null ? "" : dir.trim()); }

    // ---- Virtual Portrait asset library (control-plane OpenAPI, AK/SK) ----

    public String accessKey() { return envOr("access_key", "BYTEPLUS_ACCESS_KEY"); }
    public void setAccessKey(String v) { set("access_key", v); }

    public String secretKey() { return envOr("secret_key", "BYTEPLUS_SECRET_KEY"); }
    public void setSecretKey(String v) { set("secret_key", v); }

    public String storedAccessKey() { return props.getProperty("access_key", ""); }
    public String storedSecretKey() { return props.getProperty("secret_key", ""); }

    /** Assets are isolated per project: must be the project of the API key used for video generation. */
    public String projectName() { return props.getProperty("project_name", "").trim(); }
    public void setProjectName(String v) { set("project_name", v); }

    public String openApiHost() { return props.getProperty("openapi_host", "").trim(); }

    public String assetGroup() { return props.getProperty("asset_group", "").trim(); }
    public void setAssetGroup(String v) { set("asset_group", v); }

    public boolean autoPush() { return Boolean.parseBoolean(props.getProperty("auto_push", "true")); }
    public void setAutoPush(boolean v) { props.setProperty("auto_push", String.valueOf(v)); }

    /** Bucket TOS riêng tư app tự tạo để đưa ảnh trên máy lên Virtual Portrait (CreateAsset chỉ nhận URL). */
    public String tosBucket() { return props.getProperty("tos_bucket", "").trim(); }
    public void setTosBucket(String v) { set("tos_bucket", v); }

    public String tosRegion() {
        String r = props.getProperty("tos_region", "").trim();
        return r.isEmpty() ? "ap-southeast-1" : r;
    }

    // ---- BOTSD (botsd.pro) – nguồn tạo video thứ hai, đăng nhập bằng tài khoản BOTSD ----

    public String botsdBaseUrl() {
        String u = props.getProperty("botsd_base_url", "").trim();
        return u.isEmpty() ? "https://botsd.pro" : u;
    }

    public String botsdUsername() { return props.getProperty("botsd_username", "").trim(); }
    public void setBotsdUsername(String v) { set("botsd_username", v); }

    public String botsdPassword() { return props.getProperty("botsd_password", ""); }
    public void setBotsdPassword(String v) { props.setProperty("botsd_password", v == null ? "" : v); }

    /** clientToken nhận khi đăng nhập (hết hạn → app tự đăng nhập lại bằng tài khoản đã lưu). */
    public String botsdToken() { return props.getProperty("botsd_token", "").trim(); }
    public void setBotsdToken(String v) { set("botsd_token", v); }

    // ---- 888API (888api.xin) – nguồn tạo video thứ ba, xác thực bằng API key "sk-…" ----

    public String api888BaseUrl() {
        String u = props.getProperty("api888_base_url", "").trim();
        return u.isEmpty() ? "https://888api.xin" : u;
    }

    public String api888Key() { return props.getProperty("api888_key", "").trim(); }
    public void setApi888Key(String v) { set("api888_key", v); }

    /** Nơi để link ảnh tham chiếu cho 888API (chỉ nhận link HTTPS): TOS (bucket riêng tư BytePlus, mặc định) hoặc LITTERBOX. */
    public String api888ImageHost() {
        String h = props.getProperty("api888_image_host", "").trim();
        return h.isEmpty() ? "TOS" : h;
    }
    public void setApi888ImageHost(String v) { set("api888_image_host", v); }

    private void set(String k, String v) { props.setProperty(k, v == null ? "" : v.trim()); }

    private String envOr(String key, String env) {
        String v = props.getProperty(key, "").trim();
        if (v.isEmpty()) {
            String e = System.getenv(env);
            if (e != null) v = e.trim();
        }
        return v;
    }
}
