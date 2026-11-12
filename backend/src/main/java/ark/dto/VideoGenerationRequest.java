package ark.dto;

import java.util.List;

import ark.integration.byteplus.Models.VideoMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** POST /api/v1/videos/generations – model-specific limits are checked again in the service. */
@Data
public class VideoGenerationRequest {
    @NotBlank(message = "Chưa chọn model")
    private String model;

    @NotNull(message = "Chưa chọn chế độ")
    private VideoMode mode = VideoMode.TEXT;

    @Size(max = 4000, message = "Prompt tối đa 4000 ký tự")
    private String prompt;

    private String firstFrame;
    private String lastFrame;

    @Size(max = 30, message = "Tối đa 30 ảnh tham chiếu")
    private List<@NotBlank String> refImages;

    @Size(max = 10, message = "Tối đa 10 video tham chiếu")
    private List<@NotBlank String> refVideos;

    @Size(max = 10, message = "Tối đa 10 audio tham chiếu")
    private List<@NotBlank String> refAudios;

    @Pattern(regexp = "480p|720p|1080p|4k", message = "Độ phân giải không hợp lệ")
    private String resolution;

    @Pattern(regexp = "adaptive|16:9|9:16|1:1|4:3|3:4|21:9", message = "Tỉ lệ khung không hợp lệ")
    private String ratio;

    @Min(value = -1, message = "Thời lượng phải từ -1 trở lên")
    @Max(value = 30, message = "Thời lượng tối đa 30 giây")
    private Integer duration;

    private boolean generateAudio = true;
    private boolean watermark;

    @Min(-1)
    @Max(2147483647L)
    private Long seed;

    private boolean cameraFixed;
    private boolean returnLastFrame;

    @Pattern(regexp = "mp4|mov", message = "outputFormat phải là mp4 hoặc mov")
    private String outputFormat;
}
