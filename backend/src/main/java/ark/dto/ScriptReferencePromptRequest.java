package ark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Sửa prompt ảnh của một mã; generate = true thì tạo lại ảnh ngay. */
@Data
public class ScriptReferencePromptRequest {
    @NotBlank(message = "Prompt ảnh không được trống")
    @Size(max = 6000, message = "Prompt ảnh tối đa 6000 ký tự")
    private String prompt;

    private boolean generate;
}
