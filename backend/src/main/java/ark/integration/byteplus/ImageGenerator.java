package ark.integration.byteplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Builds a Seedream request, calls POST /images/generations and saves the images locally. */
public final class ImageGenerator {
    private ImageGenerator() {}

    public static final class Params {
        public Models.ImageModel model;
        public String prompt = "";
        public List<String> refs = List.of();   // local paths / URLs
        public String size;                      // "2K", "2048x2048", ... (blank = model default)
        public boolean sequential;               // batch of related images
        public int maxImages = 4;
        public boolean watermark;
        public String outputFormat;              // "jpeg" / "png" (5.0 models only)
    }

    public static Map<String, Object> buildBody(Params p) throws IOException {
        if (p.model == null) throw new IllegalArgumentException("Chưa chọn model");
        if (p.prompt == null || p.prompt.isBlank()) throw new IllegalArgumentException("Prompt không được để trống");
        if (p.refs.size() > p.model.maxRefs()) {
            throw new IllegalArgumentException(p.model.name() + " chỉ nhận tối đa " + p.model.maxRefs() + " ảnh tham chiếu");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", p.model.id());
        body.put("prompt", p.prompt.trim());
        if (!p.refs.isEmpty()) {
            List<String> imgs = new ArrayList<>();
            for (String r : p.refs) imgs.add(Media.imageRef(r));
            body.put("image", imgs.size() == 1 ? imgs.get(0) : imgs);
        }
        if (p.size != null && !p.size.isBlank()) body.put("size", p.size.trim());
        if (p.model.sequential()) {
            if (p.sequential) {
                int max = Math.max(1, Math.min(p.maxImages, 15 - p.refs.size()));
                body.put("sequential_image_generation", "auto");
                body.put("sequential_image_generation_options", Map.of("max_images", max));
            } else {
                body.put("sequential_image_generation", "disabled");
            }
        }
        if (p.model.outputFormat() && p.outputFormat != null && !p.outputFormat.isBlank()) {
            body.put("output_format", p.outputFormat);
        }
        body.put("response_format", "url");
        body.put("watermark", p.watermark);
        return body;
    }

    /** Runs the request and saves every returned image into outDir/images. */
    public static List<SavedOutput> run(ArkClient client, Params p, Path outDir, Consumer<String> log)
            throws IOException, InterruptedException {
        Map<String, Object> body = buildBody(p);
        log.accept("Gửi yêu cầu tạo ảnh tới " + p.model.name() + "…");
        long t0 = System.currentTimeMillis();
        Map<String, Object> resp = client.generateImages(body);
        log.accept(String.format("Nhận phản hồi sau %.1f giây", (System.currentTimeMillis() - t0) / 1000.0));

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String defaultExt = p.model.outputFormat() && "png".equals(p.outputFormat) ? "png" : "jpeg";
        Path dir = outDir.resolve("images");
        Files.createDirectories(dir);
        List<SavedOutput> saved = new ArrayList<>();
        List<?> data = Json.list(resp, "data");
        for (int i = 0; i < data.size(); i++) {
            Object item = data.get(i);
            String errMsg = Json.str(item, "error", "message");
            if (errMsg != null) {
                log.accept("Ảnh #" + (i + 1) + " lỗi: " + Json.str(item, "error", "code") + " – " + errMsg);
                continue;
            }
            String fmt = Json.str(item, "output_format");
            String ext = fmt != null ? ("jpg".equals(fmt) ? "jpeg" : fmt) : defaultExt;
            Path target = dir.resolve("IMG_" + stamp + "_" + (i + 1) + "." + ext);
            String url = Json.str(item, "url");
            String b64 = Json.str(item, "b64_json");
            if (url != null) {
                client.download(url, target);
            } else if (b64 != null) {
                Files.write(target, Base64.getDecoder().decode(b64));
            } else {
                continue;
            }
            saved.add(new SavedOutput(target, url));
            log.accept("Đã lưu " + target + "  (" + Json.str(item, "size") + ")");
        }
        if (saved.isEmpty()) {
            String msg = Json.str(resp, "error", "message");
            throw new IOException(msg != null ? msg : "API không trả về ảnh nào");
        }
        Long tokens = Json.lng(resp, "usage", "total_tokens");
        log.accept("Xong: " + saved.size() + " ảnh" + (tokens != null ? ", " + tokens + " tokens" : ""));
        return saved;
    }
}
