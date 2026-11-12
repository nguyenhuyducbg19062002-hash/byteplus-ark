package ark;

import java.io.IOException;

/** HTTP error returned by the ModelArk API. */
public class ArkException extends IOException {
    private final int status;
    private final String code;
    private final String requestId;

    public ArkException(int status, String code, String message, String requestId) {
        super(message);
        this.status = status;
        this.code = code;
        this.requestId = requestId;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String requestId() { return requestId; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("HTTP ").append(status);
        if (code != null) sb.append(" [").append(code).append(']');
        sb.append(": ").append(getMessage());
        if (requestId != null && !getMessage().contains(requestId)) sb.append(" (request id: ").append(requestId).append(')');
        return sb.toString();
    }
}
