package ark;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Control-plane OpenAPI client (Access Key / Secret Key, HMAC-SHA256 signature).
 * Used for the private asset library (Virtual Portrait): CreateAssetGroup, CreateAsset, GetAsset, ListAssets...
 * Docs: docs/api/private-virtual-avatar-library-guide-preview.md, docs/api/*-asset*-api.md
 */
public final class OpenApiClient {
    public static final String DEFAULT_HOST = "ark.ap-southeast-1.byteplusapi.com";
    private static final String REGION = "ap-southeast-1";
    private static final String SERVICE = "ark";
    private static final String VERSION = "2024-01-01";
    private static final DateTimeFormatter X_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final String accessKey;
    private final String secretKey;
    private final String host;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public OpenApiClient(String accessKey, String secretKey, String host) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Chưa có Access Key / Secret Key (AK/SK). Nhập trong tab Cài đặt – "
                    + "API asset (Virtual Portrait) không dùng API key thường.");
        }
        this.accessKey = accessKey.trim();
        this.secretKey = secretKey.trim();
        this.host = host == null || host.isBlank() ? DEFAULT_HOST : host.trim();
    }

    public static OpenApiClient from(Config c) {
        return new OpenApiClient(c.accessKey(), c.secretKey(), c.openApiHost());
    }

    /** Calls POST https://host/?Action=...&Version=2024-01-01 and returns the "Result" object. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(String action, Map<String, Object> body) throws IOException, InterruptedException {
        String payload = Json.write(body == null ? Map.of() : body);
        String query = "Action=" + enc(action) + "&Version=" + enc(VERSION);
        String xDate = ZonedDateTime.now(ZoneOffset.UTC).format(X_DATE);
        String contentType = "application/json";
        String payloadHash = hex(sha256(payload.getBytes(StandardCharsets.UTF_8)));
        String authorization = authorization(accessKey, secretKey, host, query, contentType, payloadHash, xDate);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + host + "/?" + query))
                .timeout(Duration.ofMinutes(1))
                .header("Content-Type", contentType)
                .header("X-Date", xDate)
                .header("X-Content-Sha256", payloadHash)
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = r.body() == null ? "" : r.body();
        Object parsed = null;
        try {
            if (!text.isBlank()) parsed = Json.parse(text);
        } catch (IllegalArgumentException ignored) {
            // non-JSON body
        }
        String errCode = Json.str(parsed, "ResponseMetadata", "Error", "Code");
        if (r.statusCode() >= 400 || errCode != null) {
            String msg = Json.str(parsed, "ResponseMetadata", "Error", "Message");
            if (msg == null) msg = text.isBlank() ? "HTTP " + r.statusCode() : ArkClient.truncate(text, 500);
            throw new ArkException(r.statusCode(), errCode, action + ": " + msg, Json.str(parsed, "ResponseMetadata", "RequestId"));
        }
        Object result = Json.path(parsed, "Result");
        return result instanceof Map ? (Map<String, Object>) result : new LinkedHashMap<>();
    }

    /** BytePlus / Volcengine V4 signature (HMAC-SHA256, scope date/region/service/request). */
    static String authorization(String accessKey, String secretKey, String host, String query, String contentType,
                                String payloadHash, String xDate) {
        String date = xDate.substring(0, 8);
        String signedHeaders = "content-type;host;x-content-sha256;x-date";
        String canonicalHeaders = "content-type:" + contentType + "\n"
                + "host:" + host + "\n"
                + "x-content-sha256:" + payloadHash + "\n"
                + "x-date:" + xDate + "\n";
        String canonicalRequest = "POST\n/\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + REGION + "/" + SERVICE + "/request";
        String stringToSign = "HMAC-SHA256\n" + xDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(secretKey.getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, REGION);
        k = hmac(k, SERVICE);
        k = hmac(k, "request");
        return "HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + hex(hmac(k, stringToSign));
    }

    static String sha256Hex(String s) {
        return hex(sha256(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
