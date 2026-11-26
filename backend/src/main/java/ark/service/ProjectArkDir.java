package ark.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import ark.dto.ScriptProjectUpdateRequest;
import ark.entity.ScriptProject;
import ark.integration.byteplus.Json;
import lombok.extern.slf4j.Slf4j;

/**
 * Thư mục ẩn {@code <dự án>/.ark/} của dự án theo thư mục: {@code project.json} giữ file Excel đã chọn và cài đặt chạy
 * (nguồn video, model, tỉ lệ…) để mở lại thư mục – kể cả trên máy khác / sau khi xoá dữ liệu app – vẫn có lại đủ.
 * Không ghi key / mật khẩu vào đây.
 */
@Slf4j
public final class ProjectArkDir {
    public static final String DIR = ".ark";
    private static final String FILE = "project.json";

    private ProjectArkDir() {}

    public static Path dir(String rootDir) {
        return Path.of(rootDir).resolve(DIR);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String rootDir) {
        try {
            Path f = dir(rootDir).resolve(FILE);
            if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
            Object v = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            return v instanceof Map ? new LinkedHashMap<>((Map<String, Object>) v) : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("Không đọc được {}/{}: {}", DIR, FILE, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** Ghi project.json (ghi file tạm rồi đổi tên). Lỗi ghi (thư mục chỉ đọc…) chỉ ghi log, không làm hỏng thao tác chính. */
    public static void write(ScriptProject p) {
        if (!p.isFolderProject()) return;
        try {
            Path d = dir(p.getRootDir());
            Files.createDirectories(d);
            try {
                Files.setAttribute(d, "dos:hidden", true);
            } catch (Exception ignored) {
                // không phải Windows / không đặt được thuộc tính ẩn
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("app", "Ark Studio");
            m.put("name", p.getName());
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("scenes", p.getSceneFile());
            files.put("references", p.getRefFile());
            files.put("voice", p.getVoiceFile());
            m.put("files", files);
            m.put("settings", settings(p));
            Path tmp = d.resolve(FILE + ".tmp");
            Files.writeString(tmp, Json.write(m), StandardCharsets.UTF_8);
            Files.move(tmp, d.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("Không ghi được {}/{} trong {}: {}", DIR, FILE, p.getRootDir(), e.getMessage());
        }
    }

    static Map<String, Object> settings(ScriptProject p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("provider", p.getProvider());
        s.put("model", p.getModel());
        s.put("resolution", p.getResolution());
        s.put("ratio", p.getRatio());
        s.put("generateAudio", p.isGenerateAudio());
        s.put("maxParallel", p.getMaxParallel());
        s.put("maxAttempts", p.getMaxAttempts());
        s.put("botsdModel", p.getBotsdModel());
        s.put("botsdResolution", p.getBotsdResolution());
        s.put("botsdRealPerson", p.getBotsdRealPerson());
        s.put("api888Model", p.getApi888Model());
        s.put("imageModel", p.getImageModel());
        s.put("imageSize", p.getImageSize());
        s.put("promptModel", p.getPromptModel());
        s.put("autoApproveImages", p.isAutoApproveImages());
        return s;
    }

    /** Cài đặt đã lưu trong project.json → yêu cầu cập nhật dự án (null nếu không có). */
    public static ScriptProjectUpdateRequest savedSettings(Map<String, Object> json) {
        Object s = json.get("settings");
        if (!(s instanceof Map<?, ?> m) || m.isEmpty()) return null;
        ScriptProjectUpdateRequest r = new ScriptProjectUpdateRequest();
        r.setProvider(str(m.get("provider")));
        r.setModel(str(m.get("model")));
        r.setResolution(str(m.get("resolution")));
        r.setRatio(str(m.get("ratio")));
        r.setGenerateAudio(bool(m.get("generateAudio")));
        r.setMaxParallel(num(m.get("maxParallel")));
        r.setMaxAttempts(num(m.get("maxAttempts")));
        r.setBotsdModel(str(m.get("botsdModel")));
        r.setBotsdResolution(str(m.get("botsdResolution")));
        r.setBotsdRealPerson(str(m.get("botsdRealPerson")));
        r.setApi888Model(str(m.get("api888Model")));
        r.setImageModel(str(m.get("imageModel")));
        r.setImageSize(str(m.get("imageSize")));
        r.setPromptModel(str(m.get("promptModel")));
        r.setAutoApproveImages(bool(m.get("autoApproveImages")));
        return r;
    }

    /** File Excel đã chọn lần trước: "scenes" / "references" / "voice" → tên file. */
    public static String savedFile(Map<String, Object> json, String key) {
        Object f = json.get("files");
        return f instanceof Map<?, ?> m ? str(m.get(key)) : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean bool(Object o) {
        return o instanceof Boolean b ? b : o == null ? null : Boolean.valueOf(String.valueOf(o));
    }

    private static Integer num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Ghi lại sau khi dự án đổi cài đặt. */
    public static void saveQuietly(ScriptProject p) {
        try {
            write(p);
        } catch (RuntimeException e) {
            log.warn("project.json: {}", e.getMessage());
        }
    }
}
