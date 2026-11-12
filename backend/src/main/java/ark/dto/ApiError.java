package ark.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Uniform error body. fields = validation errors per field. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String code, Integer upstreamStatus, String requestId, Map<String, String> fields) {
    public static ApiError of(String message) {
        return new ApiError(message, null, null, null, null);
    }
}
