package ark.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.entity.ScriptProject;
import ark.entity.ScriptReference;
import ark.integration.byteplus.ArkClient;
import ark.integration.byteplus.ArkException;
import ark.integration.byteplus.Json;
import ark.integration.byteplus.Models;
import lombok.RequiredArgsConstructor;

/**
 * Dùng model ngôn ngữ Dola-Seed (Chat API) viết prompt Seedream cho ảnh tham chiếu từ mô tả trong kịch bản.
 * Model chỉ trả về chữ – ảnh vẫn do Seedream tạo. Bản nháp tự sinh (ScriptPromptBuilder) được gửi kèm để giữ bố cục chuẩn.
 */
@Service
@RequiredArgsConstructor
public class ScriptPromptWriter {
    private final Config config;

    private static final String SYSTEM = """
            You write prompts for the Seedream text-to-image model. The images are REFERENCE images for a live-action \
            film that will later be animated with Seedance, so they must be clean, consistent and photorealistic.
            Rules:
            - Output ONLY the final prompt in English: no title, no quotes, no markdown, no explanation.
            - 120 to 220 words, one paragraph, concrete visual details (age, ethnicity, face, hair, body, clothing \
            materials and colours, textures, lighting, lens).
            - The source descriptions may be in Vietnamese: translate them faithfully, never invent story events, \
            never add other characters.
            - Keep every framing / background / "no text, no logos, no watermark" constraint of the DRAFT prompt.
            - CHARACTER: one person only, full body head to shoes, standing, facing the camera, neutral expression, \
            plain light-grey studio background, nothing in the hands.
            - CHARACTER with a face reference: start by saying to keep exactly the same person as the reference image \
            (identical face, hairstyle, skin tone, age, body shape) and change only the outfit.
            - LOCATION: empty establishing shot, no people or animals, no readable signs.
            - PROP: a single object centred on a plain neutral background, whole object visible, no hands.
            """;

    /** @return prompt mới (chưa lưu) */
    public String write(ScriptProject project, ScriptReference r) throws IOException, InterruptedException {
        Models.TextModel model = Models.text(project.getPromptModel());
        StringBuilder user = new StringBuilder();
        user.append("Type: ").append(r.getKind()).append('\n');
        user.append("Code: ").append(r.getCode()).append('\n');
        if (r.getName() != null) user.append("Name: ").append(r.getName()).append('\n');
        if (r.getDescription() != null) user.append("Description from the script: ").append(r.getDescription()).append('\n');
        if (r.getIdentityCode() != null) {
            user.append("Face reference: the image of ").append(r.getIdentityCode())
                    .append(" is attached when generating – same person, different outfit.\n");
        }
        if (r.getImagePrompt() != null && !r.getImagePrompt().isBlank()) {
            user.append("\nDRAFT prompt (improve it, keep its constraints):\n").append(r.getImagePrompt()).append('\n');
        }
        user.append("\nWrite the final Seedream prompt now.");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM));
        messages.add(Map.of("role", "user", "content", user.toString()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.id());
        body.put("messages", messages);
        body.put("max_tokens", 2000);
        body.put("temperature", 0.4);
        body.put("thinking", Map.of("type", "disabled")); // chỉ cần viết lại, không cần suy luận dài (nhanh + rẻ)

        ArkClient client = ArkClient.from(config);
        Map<String, Object> res;
        try {
            res = client.chat(body);
        } catch (ArkException e) {
            String code = e.code() == null ? "" : e.code().toLowerCase(Locale.ROOT);
            if (code.contains("modelnotopen")) {
                throw new IllegalStateException("Tài khoản chưa bật model " + model.name() + " (" + model.id()
                        + "). Cách sửa: console.byteplus.com → ModelArk → Model activation → tìm " + model.id()
                        + " → Activate rồi bấm lại; hoặc mở “Cấu hình chạy” chọn model viết prompt khác đã bật → Lưu cấu hình.");
            }
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (e.status() == 400 && msg.contains("thinking")) { // model không nhận tham số thinking → gửi lại không có
                body.remove("thinking");
                res = client.chat(body);
            } else {
                throw e;
            }
        }
        String text = Json.str(res, "choices", 0, "message", "content");
        String prompt = clean(text);
        if (prompt.isBlank()) throw new IllegalStateException("Model " + model.name() + " không trả về prompt – thử lại hoặc đổi model");
        return prompt;
    }

    /** Bỏ phần suy nghĩ / markdown / ngoặc kép model có thể thêm vào. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<think>.*?</think>", "").trim();
        t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        t = t.replaceFirst("(?i)^(final )?(seedream )?prompt\\s*:\\s*", "").trim();
        if (t.length() > 1 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("“") && t.endsWith("”"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t.length() > 6000 ? t.substring(0, 6000) : t;
    }
}
