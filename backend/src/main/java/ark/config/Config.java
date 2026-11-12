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
