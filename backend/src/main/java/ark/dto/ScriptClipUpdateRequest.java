package ark.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** PUT /api/v1/script-clips/{id} – sửa prompt / thời lượng; run = true thì đưa vào hàng đợi chạy ngay. */
@Data
public class ScriptClipUpdateRequest {
    @NotBlank(message = "Prompt không được trống")
    @Size(max = 12000, message = "Prompt tối đa 12000 ký tự")
    private String prompt;

    @Min(value = 4, message = "Thời lượng tối thiểu 4 giây")
    @Max(value = 30, message = "Thời lượng tối đa 30 giây")
    private Integer duration;

    private boolean run;
}
