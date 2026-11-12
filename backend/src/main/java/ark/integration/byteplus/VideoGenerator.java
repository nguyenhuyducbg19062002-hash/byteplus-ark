package ark.integration.byteplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ark.integration.byteplus.Models.VideoMode;
import ark.integration.byteplus.Models.VideoModel;

/** Builds a Seedance task, polls it until done, and downloads the result. */
public final class VideoGenerator {
    private VideoGenerator() {}

    public static final Set<String> TERMINAL = Set.of("succeeded", "failed", "expired", "cancelled");
    public static final long POLL_MS = 5_000;

    public static final class Params {
        public VideoModel model;
        public VideoMode mode = VideoMode.TEXT;
        public String prompt = "";
        public String firstFrame;
        public String lastFrame;
        public List<String> refImages = List.of();
        public List<String> refVideos = List.of();
        public List<String> refAudios = List.of();
        public String resolution;
        public String ratio;
        public Integer duration;
        public boolean generateAudio = true;
        public boolean watermark;
        public Long seed;
        public boolean cameraFixed;
        public boolean returnLastFrame;
        public String outputFormat;   // mp4 / mov (Seedance 2.5)
    }

    public static Map<String, Object> buildBody(Params p, Consumer<String> log) throws IOException {
        VideoModel m = p.model;
        if (m == null) throw new IllegalArgumentException("Chưa chọn model");
        if (!m.supports(p.mode)) throw new IllegalArgumentException(m.name() + " không hỗ trợ chế độ: " + p.mode.label);
        String prompt = p.prompt == null ? "" : p.prompt.trim();

        List<Map<String, Object>> content = new ArrayList<>();
        if (!prompt.isEmpty()) content.add(Map.of("type", "text", "text", prompt));

        switch (p.mode) {
            case TEXT:
                if (prompt.isEmpty()) throw new IllegalArgumentException("Chế độ văn bản → video cần prompt");
                break;
            case FIRST_FRAME:
                require(p.firstFrame, "Chưa chọn ảnh khung đầu");
                content.add(image(p.firstFrame, "first_frame"));
                break;
            case FIRST_LAST:
                require(p.firstFrame, "Chưa chọn ảnh khung đầu");
                require(p.lastFrame, "Chưa chọn ảnh khung cuối");
                content.add(image(p.firstFrame, "first_frame"));
                content.add(image(p.lastFrame, "last_frame"));
                break;
            case OMNI:
                if (p.refImages.size() > m.maxRefImages()) throw new IllegalArgumentException("Tối đa " + m.maxRefImages() + " ảnh tham chiếu");
                if (p.refVideos.size() > m.maxRefVideos()) throw new IllegalArgumentException("Tối đa " + m.maxRefVideos() + " video tham chiếu");
                if (p.refAudios.size() > m.maxRefAudios()) throw new IllegalArgumentException("Tối đa " + m.maxRefAudios() + " audio tham chiếu");
                if (p.refImages.isEmpty() && p.refVideos.isEmpty() && p.refAudios.isEmpty()) {
                    throw new IllegalArgumentException("Chế độ tham chiếu cần ít nhất 1 ảnh / video / audio");
                }
                if (!m.is25() && p.refImages.isEmpty() && p.refVideos.isEmpty()) {
                    throw new IllegalArgumentException(m.name() + " không nhận chỉ audio – cần thêm ít nhất 1 ảnh hoặc video");
                }
                for (String s : p.refImages) content.add(image(s, "reference_image"));
                for (String s : p.refVideos) {
                    content.add(Map.of("type", "video_url", "video_url", Map.of("url", Media.videoRef(s)), "role", "reference_video"));
                }
                for (String s : p.refAudios) {
                    content.add(Map.of("type", "audio_url", "audio_url", Map.of("url", Media.audioRef(s)), "role", "reference_audio"));
                }
                break;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", m.id());
        body.put("content", content);

        String res = blank(p.resolution) ? m.defaultResolution() : p.resolution;
        if (!m.resolutions().contains(res)) throw new IllegalArgumentException(m.name() + " không hỗ trợ độ phân giải " + res);
        body.put("resolution", res);

        String ratio = blank(p.ratio) ? "adaptive" : p.ratio;
        if (m.is25() && (p.mode == VideoMode.FIRST_FRAME || p.mode == VideoMode.FIRST_LAST) && !"adaptive".equals(ratio)) {
            log.accept("Seedance 2.5 image-to-video chỉ hỗ trợ ratio = adaptive (giữ tỉ lệ ảnh khung đầu) → tự đổi sang adaptive");
            ratio = "adaptive";
        }
        if (m.id().startsWith("seedance-1-0") && p.mode == VideoMode.TEXT && "adaptive".equals(ratio)) {
            log.accept("Seedance 1.0 text-to-video không hỗ trợ ratio adaptive → dùng 16:9");
            ratio = "16:9";
        }
        body.put("ratio", ratio);

        int dur = p.duration == null ? m.defaultDuration() : p.duration;
        if (dur == -1) {
            if (!m.autoDuration()) throw new IllegalArgumentException(m.name() + " không hỗ trợ duration = -1 (tự chọn)");
        } else if (dur < m.minDuration() || dur > m.maxDuration()) {
            throw new IllegalArgumentException(m.name() + ": thời lượng phải trong [" + m.minDuration() + ", " + m.maxDuration() + "] giây"
                    + (m.autoDuration() ? " hoặc -1" : ""));
        }
        body.put("duration", dur);

        if (m.audio()) body.put("generate_audio", p.generateAudio);
        body.put("watermark", p.watermark);
        if (m.seedAndCamera()) {
            if (p.seed != null) body.put("seed", p.seed);
            if (p.cameraFixed) body.put("camera_fixed", true);
        }
        if (p.returnLastFrame) body.put("return_last_frame", true);
        if (m.is25() && !blank(p.outputFormat)) body.put("output_format", p.outputFormat);
        return body;
    }

    /** Creates the task and returns its ID. */
    public static String submit(ArkClient client, Params p, Consumer<String> log) throws IOException, InterruptedException {
        Map<String, Object> body = buildBody(p, log);
        log.accept("Tạo task video với " + p.model.name() + " (" + body.get("resolution") + ", " + body.get("ratio")
                + ", " + body.get("duration") + "s)…");
        Map<String, Object> resp = client.createVideoTask(body);
        String id = Json.str(resp, "id");
        if (id == null) throw new IOException("API không trả về task ID: " + Json.write(resp));
        log.accept("Task ID: " + id);
        return id;
    }

    /** Polls until the task reaches a terminal status (or cancelled() returns true → returns null). */
    public static Map<String, Object> waitFor(ArkClient client, String id, Consumer<String> log, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String last = null;
        long t0 = System.currentTimeMillis();
        while (true) {
            if (cancelled.getAsBoolean()) {
                log.accept("Đã dừng theo dõi task " + id + " (task vẫn chạy trên server, xem lại ở tab Danh sách task)");
                return null;
            }
            Map<String, Object> task = client.getVideoTask(id);
            String status = String.valueOf(Json.str(task, "status"));
            if (!status.equals(last)) {
                log.accept(String.format("[%ds] Trạng thái: %s", (System.currentTimeMillis() - t0) / 1000, status));
                last = status;
            }
            if (TERMINAL.contains(status)) return task;
            long until = System.currentTimeMillis() + POLL_MS;
            while (System.currentTimeMillis() < until) {
                if (cancelled.getAsBoolean()) break;
                Thread.sleep(500);
            }
        }
    }

    /** Downloads video (+ last frame if present) of a succeeded task into outDir/videos. */
    public static List<SavedOutput> downloadOutputs(ArkClient client, Map<String, Object> task, Path outDir, Consumer<String> log)
            throws IOException, InterruptedException {
        String id = Json.str(task, "id");
        String status = Json.str(task, "status");
        if (!"succeeded".equals(status)) {
            String err = Json.str(task, "error", "message");
            throw new IOException("Task " + id + " không thành công: " + status
                    + (err != null ? " – " + Json.str(task, "error", "code") + ": " + err : ""));
        }
        Path dir = outDir.resolve("videos");
        Files.createDirectories(dir);
        List<SavedOutput> files = new ArrayList<>();
        String videoUrl = Json.str(task, "content", "video_url");
        if (videoUrl != null) {
            String fmt = Json.str(task, "output_format");
            Path target = dir.resolve(id + "." + (fmt == null ? "mp4" : fmt));
            log.accept("Đang tải video…");
            files.add(new SavedOutput(client.download(videoUrl, target), videoUrl));
            log.accept("Đã lưu video: " + target);
        }
        String frameUrl = Json.str(task, "content", "last_frame_url");
        if (frameUrl != null) {
            Path target = dir.resolve(id + "_last_frame.jpeg");
            files.add(new SavedOutput(client.download(frameUrl, target), frameUrl));
            log.accept("Đã lưu khung cuối: " + target);
        }
        Long tokens = Json.lng(task, "usage", "total_tokens");
        if (tokens != null) log.accept("Tokens sử dụng: " + tokens);
        return files;
    }

    private static Map<String, Object> image(String input, String role) throws IOException {
        return Map.of("type", "image_url", "image_url", Map.of("url", Media.imageRef(input)), "role", role);
    }

    private static void require(String s, String msg) {
        if (blank(s)) throw new IllegalArgumentException(msg);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
