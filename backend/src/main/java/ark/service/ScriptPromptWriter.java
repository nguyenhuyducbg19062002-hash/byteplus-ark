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

        String prompt = clean(chat(model, SYSTEM, user.toString(), 2000, 0.4));
        if (prompt.isBlank()) throw new IllegalStateException("Model " + model.name() + " không trả về prompt – thử lại hoặc đổi model");
        return prompt;
    }

    private static final String BLOCK_SYSTEM = """
            Bạn là chuyên gia kiểm duyệt nội dung cho model tạo video Seedance (BytePlus). Một clip vừa bị hệ thống \
            kiểm duyệt chặn. Hãy đọc prompt (tiếng Anh) và lời thoại, tìm CHÍNH XÁC các đoạn có khả năng gây chặn \
            (bạo lực, máu me, vũ khí, tình dục / hở hang, trẻ em trong tình huống nhạy cảm, ma tuý, tự hại, thù ghét, \
            chính trị, người nổi tiếng / thương hiệu / bản quyền, lời thoại tục tĩu hoặc đe doạ…).
            Trả về DUY NHẤT một object JSON (không markdown, không giải thích ngoài JSON) theo mẫu:
            {"category": "loại vi phạm chính, tiếng Việt, ngắn",
             "summary": "1-2 câu tiếng Việt giải thích vì sao bị chặn",
             "issues": [{"quote": "trích NGUYÊN VĂN đoạn trong prompt hoặc lời thoại", \
            "where": "prompt" hoặc "lời thoại", "vi": "dịch đoạn đó sang tiếng Việt", \
            "reason": "vì sao đoạn này dễ bị chặn, tiếng Việt", "fix": "cách viết lại đoạn này (tiếng Anh nếu là prompt)"}],
             "fixedPrompt": "toàn bộ prompt tiếng Anh đã sửa: giữ nguyên cốt truyện, nhân vật, Image 1/Image 2…, \
            máy quay, thời lượng; chỉ làm dịu các đoạn nghi vấn"}
            Quy tắc: "quote" phải là chuỗi có thật trong văn bản được cung cấp; tối đa 6 issues, đoạn nguy cơ cao nhất trước. \
            Nếu lỗi là về âm thanh (OutputAudio…) thì ưu tiên soi lời thoại và mô tả âm thanh. Nếu không thấy đoạn nào rõ ràng, \
            issues để rỗng và nói rõ trong summary rằng có thể bị chặn ngẫu nhiên, nên thử tạo lại.
            """;

    /**
     * Phân tích vì sao clip bị kiểm duyệt chặn: loại vi phạm, đoạn nghi vấn (trích nguyên văn + dịch + lý do), prompt đã sửa.
     * @return chuỗi JSON (category, summary, issues[], fixedPrompt); model trả chữ thường thì gói thành {"summary": …}
     */
    public String explainBlock(ScriptProject project, String prompt, String dialogue, String errorCode, String errorMessage)
            throws IOException, InterruptedException {
        Models.TextModel model = Models.text(project.getPromptModel());
        StringBuilder user = new StringBuilder();
        user.append("Mã lỗi BytePlus: ").append(errorCode == null ? "?" : errorCode).append('\n');
        if (errorMessage != null) {
            user.append("Thông báo lỗi: ").append(errorMessage.length() > 600 ? errorMessage.substring(0, 600) : errorMessage).append('\n');
        }
        user.append("\nPROMPT gửi Seedance:\n").append(prompt == null ? "" : prompt).append('\n');
        if (dialogue != null && !dialogue.isBlank()) user.append("\nLỜI THOẠI của clip:\n").append(dialogue).append('\n');
        user.append("\nTrả về JSON theo mẫu.");
        String raw = chat(model, BLOCK_SYSTEM, user.toString(), 4000, 0.2);
        // Không dùng clean(): nó cắt 6000 ký tự và bỏ ngoặc kép – JSON có prompt đã sửa dài hơn thế
        String text = raw == null ? "" : raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                Object parsed = Json.parse(text.substring(a, b + 1));
                if (parsed instanceof Map) return Json.write(parsed);
            } catch (IllegalArgumentException ignored) {
                // không phải JSON hợp lệ → trả chữ thường bên dưới
            }
        }
        if (text.isBlank()) throw new IllegalStateException("Model " + model.name() + " không trả về phân tích – thử lại");
        return Json.write(Map.of("summary", text));
    }

    /** Gọi Chat API của model ngôn ngữ, trả về nội dung trả lời (chưa làm sạch). */
    private String chat(Models.TextModel model, String system, String user, int maxTokens, double temperature)
            throws IOException, InterruptedException {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.id());
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("thinking", Map.of("type", "disabled")); // không cần suy luận dài (nhanh + rẻ)

        ArkClient client = ArkClient.from(config);
        Map<String, Object> res;
        try {
            res = client.chat(body);
        } catch (ArkException e) {
            String code = e.code() == null ? "" : e.code().toLowerCase(Locale.ROOT);
            if (code.contains("modelnotopen")) {
                throw new IllegalStateException("Tài khoản chưa bật model ngôn ngữ " + model.name() + " (" + model.id()
                        + "). Cách sửa: console.byteplus.com → ModelArk → Model activation → tìm " + model.id()
                        + " → Activate rồi bấm lại.");
            }
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (e.status() == 400 && msg.contains("thinking")) { // model không nhận tham số thinking → gửi lại không có
                body.remove("thinking");
                res = client.chat(body);
            } else {
                throw e;
            }
        }
        return Json.str(res, "choices", 0, "message", "content");
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
