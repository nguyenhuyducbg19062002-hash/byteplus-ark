package ark.integration.api888;

import java.io.IOException;

/**
 * Lỗi HTTP từ 888API (888api.xin). status: 400/422 sai tham số (REFERENCE_MISSING, REFERENCE_UNREADABLE…),
 * 401/403 key sai / không có quyền model, 402 hết số dư, 404/410 không có task, 429 chậm lại (Retry-After), 5xx lỗi máy chủ.
 */
public class Api888Exception extends IOException {
    private final int status;
    private final String code;
    private final Integer retryAfterSeconds;

    public Api888Exception(int status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    /** Mã lỗi máy chủ trả về (vd REFERENCE_UNREADABLE), có thể null. */
    public String code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Hết số dư / hạn mức key (402 hoặc thông báo "quota is not enough"). */
    public boolean quota() {
        String m = (getMessage() + " " + code).toLowerCase(java.util.Locale.ROOT);
        return status == 402 || m.contains("quota") || m.contains("余额") || m.contains("insufficient");
    }

    @Override
    public String toString() {
        return "888API HTTP " + status + (code != null && !code.isBlank() && !getMessage().contains(code) ? " " + code : "") + ": " + getMessage();
    }
}
