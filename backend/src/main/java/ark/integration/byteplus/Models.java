package ark.integration.byteplus;

import java.util.List;

/**
 * Model IDs and capabilities, taken from docs/api/model-list.md,
 * create-video-generation-task-api.md and image-generation-api.md (Nov 2026).
 */
public final class Models {
    private Models() {}

    public enum VideoMode {
        TEXT("Văn bản → Video"),
        FIRST_FRAME("Ảnh khung đầu → Video"),
        FIRST_LAST("Khung đầu + khung cuối → Video"),
        OMNI("Tham chiếu đa phương tiện (ảnh / video / âm thanh)");

        public final String label;

        VideoMode(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    /**
     * @param autoDuration  duration = -1 allowed (model picks length)
     * @param seedAndCamera supports seed + camera_fixed (Seedance 1.x only)
     * @param is25          Dreamina Seedance 2.5 special rules (i2v ratio adaptive only, output_format)
     */
    public record VideoModel(String id, String name, List<String> resolutions, String defaultResolution,
                             int minDuration, int maxDuration, int defaultDuration, boolean autoDuration,
                             boolean audio, boolean seedAndCamera, boolean firstLast, boolean omni,
                             int maxRefImages, int maxRefVideos, int maxRefAudios, boolean is25) {
        public boolean supports(VideoMode m) {
            switch (m) {
                case FIRST_LAST: return firstLast;
                case OMNI: return omni;
                default: return true;
            }
        }

        @Override
        public String toString() { return name + "  (" + id + ")"; }
    }

    private static final List<String> R_ALL = List.of("480p", "720p", "1080p");

    public static final List<VideoModel> VIDEO = List.of(
            new VideoModel("dreamina-seedance-2-5-260628", "Seedance 2.5", R_ALL, "720p",
                    4, 30, -1, true, true, false, true, true, 30, 10, 10, true),
            new VideoModel("dreamina-seedance-2-0-260128", "Seedance 2.0", List.of("480p", "720p", "1080p", "4k"), "720p",
                    4, 15, 5, true, true, false, true, true, 9, 3, 3, false),
            new VideoModel("dreamina-seedance-2-0-fast-260128", "Seedance 2.0 fast", List.of("480p", "720p"), "720p",
                    4, 15, 5, true, true, false, true, true, 9, 3, 3, false),
            new VideoModel("dreamina-seedance-2-0-mini-260615", "Seedance 2.0 mini", List.of("480p", "720p"), "720p",
                    4, 15, 5, true, true, false, true, true, 9, 3, 3, false),
            new VideoModel("seedance-1-5-pro-251215", "Seedance 1.5 pro", R_ALL, "720p",
                    4, 12, 5, true, true, true, true, false, 0, 0, 0, false),
            new VideoModel("seedance-1-0-pro-250528", "Seedance 1.0 pro", R_ALL, "1080p",
                    2, 12, 5, false, false, true, true, false, 0, 0, 0, false),
            new VideoModel("seedance-1-0-pro-fast-251015", "Seedance 1.0 pro fast", R_ALL, "1080p",
                    2, 12, 5, false, false, true, false, false, 0, 0, 0, false));

    public static final List<String> RATIOS = List.of("adaptive", "16:9", "9:16", "1:1", "4:3", "3:4", "21:9");

    /**
     * @param sequential   supports sequential_image_generation (batch of related images)
     * @param maxRefs      max reference images
     * @param outputFormat supports output_format (png/jpeg)
     */
    public record ImageModel(String id, String name, List<String> sizes, String defaultSize,
                             boolean sequential, int maxRefs, boolean outputFormat) {
        @Override
        public String toString() { return name + "  (" + id + ")"; }
    }

    public static final List<ImageModel> IMAGE = List.of(
            new ImageModel("dola-seedream-5-5-261028", "Dola-Seedream-5.5", List.of("1K", "1.5K", "2K"), "2K", false, 10, true),
            new ImageModel("dola-seedream-5-0-flash-260915", "Dola-Seedream-5.0-flash", List.of("1K", "1.5K", "2K"), "2K", false, 10, true),
            new ImageModel("dola-seedream-5-0-pro-260628", "Dola-Seedream-5.0-pro", List.of("1K", "1.5K", "2K"), "2K", false, 10, true),
            new ImageModel("seedream-5-0-260128", "Dola-Seedream-5.0-lite", List.of("2K", "3K", "4K", "2048x2048"), "2K", true, 14, true),
            new ImageModel("seedream-4-5-251128", "ByteDance-Seedream-4.5", List.of("2K", "4K", "2048x2048"), "2K", true, 14, false),
            new ImageModel("seedream-4-0-250828", "ByteDance-Seedream-4.0", List.of("1K", "2K", "4K", "2048x2048"), "2K", true, 14, false));

    /** Model ngôn ngữ (tab Language trên console) – chỉ trả về chữ, dùng để viết prompt ảnh. */
    public record TextModel(String id, String name) {
        @Override
        public String toString() { return name + "  (" + id + ")"; }
    }

    public static final String DEFAULT_TEXT_MODEL = "seed-2-0-lite-260428";

    public static final List<TextModel> TEXT = List.of(
            new TextModel("seed-2-0-lite-260428", "Dola-Seed 2.0 lite"),
            new TextModel("seed-2-0-mini-260428", "Dola-Seed 2.0 mini"),
            new TextModel("seed-2-0-pro-260328", "Dola-Seed 2.0 pro"),
            new TextModel("dola-seed-2-1-turbo-260628", "Dola-Seed 2.1 turbo"));

    /** Model không còn trong danh sách (vd dự án lưu từ bản cũ) → dùng model mặc định thay vì báo lỗi. */
    public static TextModel text(String id) {
        for (TextModel m : TEXT) if (m.id().equals(id)) return m;
        for (TextModel m : TEXT) if (m.id().equals(DEFAULT_TEXT_MODEL)) return m;
        return TEXT.get(0);
    }

    public static VideoModel video(String id) {
        for (VideoModel m : VIDEO) if (m.id().equals(id)) return m;
        throw new IllegalArgumentException("Model video không rõ: " + id);
    }

    public static ImageModel image(String id) {
        for (ImageModel m : IMAGE) if (m.id().equals(id)) return m;
        throw new IllegalArgumentException("Model ảnh không rõ: " + id);
    }
}
