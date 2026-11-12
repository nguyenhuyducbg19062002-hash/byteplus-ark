package ark.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sinh prompt Seedream cho ảnh tham chiếu từ mô tả trong Excel (sheet NHÂN VẬT / BỐI CẢNH / ĐẠO CỤ).
 * Khung prompt bằng tiếng Anh; phần mô tả giữ nguyên văn từ kịch bản. Người dùng sửa được từng prompt trên giao diện.
 * Mã nhân vật trong mô tả (vd "nhà CHAR_WAL") được thay bằng tên nhân vật để model hiểu.
 */
public final class ScriptPromptBuilder {
    private ScriptPromptBuilder() {}

    private static final Pattern CHAR_CODE = Pattern.compile("\\bCHAR_[A-Z]{2,}\\b(?!_)");
    private static final Pattern TIME = Pattern.compile("\\[\\s*Thời điểm:\\s*([^\\]]+)]", Pattern.CASE_INSENSITIVE);

    private static final String CHAR_TAIL = " Single person only, standing upright and facing the camera, neutral relaxed expression, "
            + "arms relaxed at the sides, the whole body from head to shoes inside the frame so the complete outfit is clearly visible. "
            + "Plain seamless light-grey studio background, soft even studio lighting, sharp focus, natural realistic skin texture, "
            + "hair and fabric detail. No text, no logos, no watermark, no other people, nothing held in the hands.";

    /** Nhân vật – ảnh gốc (bộ trang phục đầu tiên). */
    public static String character(String displayName, String role, String look, String outfit, Map<String, String> names) {
        return "Photorealistic full-body character reference photo, vertical 3:4 portrait, live-action film look. "
                + "Character: " + clean(displayName, names) + (blank(role) ? "" : " – " + clean(role, names)) + ". "
                + "Appearance: " + clean(look, names) + ". "
                + "Outfit: " + clean(outfit, names) + "." + CHAR_TAIL;
    }

    /** Nhân vật – bộ trang phục khác, dùng ảnh bộ đầu làm tham chiếu khuôn mặt. */
    public static String characterVariant(String displayName, String look, String outfit, Map<String, String> names) {
        return "Photorealistic full-body character reference photo, vertical 3:4 portrait, live-action film look. "
                + "Use the reference image only as the identity reference: show the exact same person " + clean(displayName, names)
                + " with an identical face, hairstyle, skin tone, age and body shape (" + clean(look, names) + "). "
                + "Change only the clothing. New outfit: " + clean(outfit, names) + "." + CHAR_TAIL;
    }

    /** Bối cảnh – cảnh toàn, không người. */
    public static String location(String name, String description, Map<String, String> names) {
        String desc = description == null ? "" : description;
        String time = "";
        Matcher m = TIME.matcher(desc);
        if (m.find()) {
            time = " Time of day: " + m.group(1).trim() + ".";
            desc = m.replaceAll("").trim();
        }
        return "Photorealistic cinematic establishing shot of a film location, horizontal 16:9, wide angle lens, eye level, "
                + "live-action film look. Location: " + clean(name, names) + ". " + clean(desc, names) + "." + time
                + " Empty set with no people and no animals. Natural realistic lighting and materials, sharp detail, "
                + "balanced cinematic composition. No text overlays, no readable signs, no logos, no watermark.";
    }

    /** Đạo cụ – ảnh sản phẩm nền trơn. */
    public static String prop(String name, String description, Map<String, String> names) {
        String desc = description == null ? "" : description.replaceAll("\\[[^\\]]*clip[^\\]]*]", "").trim();
        return "Photorealistic prop reference photo, square 1:1. Object: " + clean(name, names) + ". " + clean(desc, names) + ". "
                + "A single object centred on a plain neutral light-grey background, the entire object visible, "
                + "soft even studio lighting, sharp focus, realistic materials and wear. No hands, no people, no text, no logos, no watermark.";
    }

    /** Mã khác (không có trong sheet mô tả). */
    public static String generic(String code, String name, String description, Map<String, String> names) {
        return "Photorealistic reference image for a live-action film: " + clean(blank(name) ? code : name, names)
                + (blank(description) ? "" : ". " + clean(description, names)) + ". Plain background, sharp focus, no text, no logos.";
    }

    private static String clean(String s, Map<String, String> names) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        Matcher m = CHAR_CODE.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(names.getOrDefault(m.group(), m.group())));
        m.appendTail(sb);
        return sb.toString().replaceAll("[.\\s]+$", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
