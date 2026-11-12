package ark.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** POST /api/v1/images/generations – refs: URL, data: URI, asset://ID or file:{id} (library file). */
@Data
public class ImageGenerationRequest {
    @NotBlank(message = "Chưa chọn model")
    private String model;

    @NotBlank(message = "Prompt không được để trống")
    @Size(max = 4000, message = "Prompt tối đa 4000 ký tự")
    private String prompt;

    @Size(max = 14, message = "Tối đa 14 ảnh tham chiếu")
    private List<@NotBlank String> refs;

    @Size(max = 20)
    private String size;

    private boolean sequential;

    @Min(1)
    @Max(15)
    private Integer maxImages = 4;

    private boolean watermark;

    @Pattern(regexp = "jpeg|png", message = "outputFormat phải là jpeg hoặc png")
    private String outputFormat;
}
