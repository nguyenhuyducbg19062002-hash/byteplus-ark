package ark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Làm lại các clip theo mã: "005, 012-015, 019A". note (tuỳ chọn) được ghép vào cuối prompt như ghi chú đạo diễn. */
@Data
public class ScriptRedoRequest {
    @NotBlank(message = "Nhập mã clip cần làm lại, vd 005, 012-015")
    @Size(max = 2000)
    private String codes;

    @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
    private String note;
}
