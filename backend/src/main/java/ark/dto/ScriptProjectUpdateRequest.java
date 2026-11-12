package ark.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** PUT /api/v1/script-projects/{id} – null = giữ nguyên. */
@Data
public class ScriptProjectUpdateRequest {
    @Size(min = 1, max = 200, message = "Tên dự án 1–200 ký tự")
    private String name;

    private String model;

    @Pattern(regexp = "480p|720p|1080p|4k", message = "Độ phân giải không hợp lệ")
    private String resolution;

    @Pattern(regexp = "adaptive|16:9|9:16|1:1|4:3|3:4|21:9", message = "Tỉ lệ khung không hợp lệ")
    private String ratio;

    private Boolean generateAudio;

    /** Model Seedream tạo ảnh tham chiếu. */
    private String imageModel;

    @Pattern(regexp = "^[0-9A-Za-z.x]{1,20}$", message = "Kích thước ảnh không hợp lệ")
    private String imageSize;

    /** true = ảnh AI tạo xong được duyệt luôn, không qua mục chờ duyệt. */
    private Boolean autoApproveImages;

    @Min(value = 1, message = "Chạy song song tối thiểu 1")
    @Max(value = 50, message = "Chạy song song tối đa 50")
    private Integer maxParallel;

    @Min(value = 1, message = "Số lần thử tối thiểu 1")
    @Max(value = 10, message = "Số lần thử tối đa 10")
    private Integer maxAttempts;
}
