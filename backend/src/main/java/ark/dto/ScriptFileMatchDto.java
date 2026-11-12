package ark.dto;

/**
 * Gợi ý ghép một file với một mã tham chiếu.
 * score: 100 = tên file chứa đúng mã, 70–95 = khớp mã rút gọn / số thứ tự theo thư mục, &lt;70 = đoán theo tên mô tả.
 * code = null → không đoán được.
 */
public record ScriptFileMatchDto(String key, String code, int score, String reason) {}
