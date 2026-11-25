package ark.integration.botsd;

import java.io.IOException;

/**
 * Lỗi HTTP từ API BOTSD (botsd.pro). status: 400 sai payload, 401 phiên hết hạn, 402 không đủ Point, 404 không có task,
 * 409 đạt giới hạn task đang chạy, 410 dữ liệu hết hạn, 429 gửi quá nhanh (retryAfterSeconds), 503 bảo trì.
 */
public class BotsdException extends IOException {
    private final int status;
    private final Integer retryAfterSeconds;

    public BotsdException(int status, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public String toString() {
        return "BOTSD HTTP " + status + ": " + getMessage();
    }
}
