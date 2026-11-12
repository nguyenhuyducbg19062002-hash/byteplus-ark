package ark.integration.byteplus;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client tối giản cho BytePlus TOS (object storage), ký bằng AK/SK (TOS4-HMAC-SHA256).
 * Dùng để đưa ảnh trên máy lên một bucket riêng tư rồi tạo URL có chữ ký (hết hạn sau vài giờ) –
 * vì CreateAsset (Virtual Portrait) chỉ nhận URL, không nhận file / base64.
 */
public final class TosClient {
    private static final DateTimeFormatter X_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String ALGO = "TOS4-HMAC-SHA256";
    private static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public TosClient(String accessKey, String secretKey, String region, String endpoint) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Chưa nhập AK/SK (Cài đặt) – cần để tải ảnh lên TOS");
        }
        this.accessKey = accessKey.trim();
        this.secretKey = secretKey.trim();
        this.region = region;
        this.endpoint = endpoint;
    }

    public static TosClient of(String ak, String sk, String region) {
        return new TosClient(ak, sk, region, "tos-" + region + ".bytepluses.com");
    }

    public String endpoint() {
        return endpoint;
    }

    /** Danh sách bucket của tài khoản (chỉ đọc) – dùng để kiểm tra AK/SK có quyền TOS. */
    public List<String> listBuckets() throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", endpoint, "/", Map.of(), null, null);
        check(r, "ListBuckets");
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\"Name\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body());
        while (m.find()) names.add(m.group(1));
        return names;
    }

    public boolean bucketExists(String bucket) throws IOException, InterruptedException {
        HttpResponse<String> r = send("HEAD", bucket + "." + endpoint, "/", Map.of(), null, null);
        if (r.statusCode() == 404) return false;
        if (r.statusCode() / 100 == 2) return true;
        throw new IOException("TOS HeadBucket " + bucket + ": HTTP " + r.statusCode());
    }

    /** Tạo bucket riêng tư (không ai đọc được nếu không có URL có chữ ký). */
    public void createBucket(String bucket) throws IOException, InterruptedException {
        HttpResponse<String> r = send("PUT", bucket + "." + endpoint, "/", Map.of("x-tos-acl", "private"), new byte[0], null);
        check(r, "CreateBucket " + bucket);
    }

    public void putObject(String bucket, String key, byte[] data, String contentType) throws IOException, InterruptedException {
        HttpResponse<String> r = send("PUT", bucket + "." + endpoint, "/" + encodePath(key), Map.of(), data, contentType);
        check(r, "PutObject " + key);
    }

    /** URL GET có chữ ký, ai có URL đều tải được tới khi hết hạn. */
    public String presignGet(String bucket, String key, Duration ttl) {
        String host = bucket + "." + endpoint;
        String path = "/" + encodePath(key);
        String xDate = ZonedDateTime.now(ZoneOffset.UTC).format(X_DATE);
        String date = xDate.substring(0, 8);
        String scope = date + "/" + region + "/tos/request";
        TreeMap<String, String> q = new TreeMap<>();
        q.put("X-Tos-Algorithm", ALGO);
        q.put("X-Tos-Credential", accessKey + "/" + scope);
        q.put("X-Tos-Date", xDate);
        q.put("X-Tos-Expires", String.valueOf(ttl.getSeconds()));
        q.put("X-Tos-SignedHeaders", "host");
        String query = canonicalQuery(q);
        String canonical = "GET\n" + path + "\n" + query + "\nhost:" + host + "\n\nhost\n" + UNSIGNED;
        String sig = sign(xDate, scope, canonical);
        return "https://" + host + path + "?" + query + "&X-Tos-Signature=" + sig;
    }

    // ---------- HTTP + chữ ký ----------

    private HttpResponse<String> send(String method, String host, String path, Map<String, String> extra, byte[] body,
                                      String contentType) throws IOException, InterruptedException {
        String xDate = ZonedDateTime.now(ZoneOffset.UTC).format(X_DATE);
        String date = xDate.substring(0, 8);
        String payloadHash = body == null ? hex(sha256(new byte[0])) : hex(sha256(body));
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-tos-date", xDate);
        headers.put("x-tos-content-sha256", payloadHash);
        if (contentType != null) headers.put("content-type", contentType);
        for (Map.Entry<String, String> e : extra.entrySet()) headers.put(e.getKey().toLowerCase(), e.getValue());
        StringBuilder canonHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) canonHeaders.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
        String signed = String.join(";", headers.keySet());
        String canonical = method + "\n" + path + "\n\n" + canonHeaders + "\n" + signed + "\n" + payloadHash;
        String scope = date + "/" + region + "/tos/request";
        String auth = ALGO + " Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed + ", Signature="
                + sign(xDate, scope, canonical);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://" + host + path)).timeout(Duration.ofMinutes(5))
                .header("Authorization", auth);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!e.getKey().equals("host")) b.header(e.getKey(), e.getValue());
        }
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        b.method(method, pub);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String sign(String xDate, String scope, String canonical) {
        String stringToSign = ALGO + "\n" + xDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(secretKey.getBytes(StandardCharsets.UTF_8), xDate.substring(0, 8));
        k = hmac(k, region);
        k = hmac(k, "tos");
        k = hmac(k, "request");
        return hex(hmac(k, stringToSign));
    }

    private static void check(HttpResponse<String> r, String what) throws IOException {
        if (r.statusCode() / 100 == 2) return;
        String body = r.body() == null ? "" : r.body();
        Matcher code = Pattern.compile("\"Code\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        Matcher msg = Pattern.compile("\"Message\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        throw new IOException("TOS " + what + ": HTTP " + r.statusCode() + (code.find() ? " [" + code.group(1) + "]" : "")
                + (msg.find() ? " " + msg.group(1) : body.length() > 200 ? " " + body.substring(0, 200) : " " + body));
    }

    private static String canonicalQuery(TreeMap<String, String> q) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String encodePath(String key) {
        StringBuilder sb = new StringBuilder();
        for (String seg : key.split("/", -1)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(enc(seg));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
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
