package ark.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ark.dto.ApiError;
import ark.integration.byteplus.ArkException;
import lombok.extern.slf4j.Slf4j;

/** Every error becomes an {@link ApiError} JSON body so the frontend can show it consistently. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        String first = fields.isEmpty() ? "Dữ liệu không hợp lệ" : fields.values().iterator().next();
        return ResponseEntity.badRequest().body(new ApiError(first, "VALIDATION", null, null, fields));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException e) {
        String msg = e.getAllErrors().isEmpty() ? "Tham số không hợp lệ" : e.getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(new ApiError(msg, "VALIDATION", null, null, null));
    }

    /** @Min/@Max on query parameters of @Validated controllers. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraint(jakarta.validation.ConstraintViolationException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), v.getMessage());
        });
        String first = fields.isEmpty() ? "Tham số không hợp lệ" : fields.entrySet().iterator().next().getKey() + ": "
                + fields.values().iterator().next();
        return ResponseEntity.badRequest().body(new ApiError(first, "VALIDATION", null, null, fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("Dữ liệu gửi lên không hợp lệ", "BAD_REQUEST", null, null, null));
    }

    /** BytePlus API error: 4xx is mostly a config/input problem → 400, otherwise 502. */
    @ExceptionHandler(ArkException.class)
    public ResponseEntity<ApiError> upstream(ArkException e) {
        HttpStatus s = e.status() >= 400 && e.status() < 500 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(s).body(new ApiError(e.getMessage(), e.code(), e.status(), e.requestId(), null));
    }

    /** Trình duyệt đóng / tua video giữa chừng → kết nối bị ngắt: bình thường, không cần trả lỗi. */
    @ExceptionHandler({org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
            org.apache.catalina.connector.ClientAbortException.class})
    public void clientAbort(Exception e) {
        log.debug("Client closed connection: {}", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(ApiError.of(String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({NoSuchElementException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> notFound(Exception e) {
        String msg = e instanceof NoResourceFoundException ? "Không có API này" : e.getMessage();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(msg));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of("Phương thức không được hỗ trợ"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(describe(e)));
    }

    /** Human-readable message for logs / job errors. */
    public static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof java.util.concurrent.ExecutionException
                || c.getClass() == RuntimeException.class)) {
            c = c.getCause();
        }
        if (c instanceof ArkException) return c.toString();
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }
}
