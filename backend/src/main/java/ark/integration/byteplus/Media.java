package ark.integration.byteplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns user input (local path / URL / asset ID) into the value the API expects. */
public final class Media {
    private Media() {}

    private static final Map<String, String> IMAGE_TYPES = Map.ofEntries(
            Map.entry("jpg", "jpeg"), Map.entry("jpeg", "jpeg"), Map.entry("png", "png"),
            Map.entry("webp", "webp"), Map.entry("bmp", "bmp"), Map.entry("tif", "tiff"),
            Map.entry("tiff", "tiff"), Map.entry("gif", "gif"), Map.entry("heic", "heic"),
            Map.entry("heif", "heif"));
    private static final Map<String, String> AUDIO_TYPES = Map.of("wav", "wav", "mp3", "mp3");

    private static final long MB = 1024L * 1024L;

    public static boolean isRemote(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("asset://") || l.startsWith("data:");
    }

    /** Image: URL, asset://, data URI, or local file (→ base64, max 30 MB). */
    public static String imageRef(String input) throws IOException {
        return ref(input, "image", IMAGE_TYPES, 30 * MB);
    }

    /** Audio: URL, asset://, data URI, or local wav/mp3 (→ base64, max 15 MB). */
    public static String audioRef(String input) throws IOException {
        return ref(input, "audio", AUDIO_TYPES, 15 * MB);
    }

    /** Video: the API only accepts a public URL or asset:// (no base64). */
    public static String videoRef(String input) throws IOException {
        String s = clean(input);
        String l = s.toLowerCase(Locale.ROOT);
        if (l.startsWith("http://") || l.startsWith("https://") || l.startsWith("asset://")) return s;
        throw new IOException("Video tham chiếu phải là URL công khai (http/https) hoặc asset://, không dùng file cục bộ: " + s);
    }

    /** Non-blank lines of a multi-line text field. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            String t = clean(line);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).trim();
        return t;
    }

    private static String ref(String input, String kind, Map<String, String> types, long maxBytes) throws IOException {
        String s = clean(input);
        if (s.isEmpty()) throw new IOException("Đường dẫn " + kind + " trống");
        if (isRemote(s)) return s;
        Path p = Path.of(s);
        if (!Files.isRegularFile(p)) throw new IOException("Không tìm thấy file: " + s);
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String sub = types.get(ext);
        if (sub == null) throw new IOException("Định dạng ." + ext + " không được hỗ trợ cho " + kind + ": " + name);
        long size = Files.size(p);
        if (size > maxBytes) throw new IOException(name + " quá lớn (" + size / MB + " MB > " + maxBytes / MB + " MB)");
        return "data:" + kind + "/" + sub + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(p));
    }
}
