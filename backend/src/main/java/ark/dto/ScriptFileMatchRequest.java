package ark.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Danh sách file ảnh trong thư mục người dùng chọn (chỉ tên + đường dẫn tương đối, chưa gửi nội dung). */
@Data
public class ScriptFileMatchRequest {
    @NotEmpty(message = "Chưa chọn file nào")
    @Size(max = 2000, message = "Tối đa 2000 file mỗi lần")
    private List<@Valid FileItem> files;

    @Data
    public static class FileItem {
        /** Khoá do frontend đặt để ghép kết quả với file. */
        @NotBlank
        private String key;

        /** Đường dẫn tương đối, vd "Anh REF/NHAN VAT/Yvonne ao navy.png". */
        @NotBlank
        @Size(max = 1000)
        private String path;
    }
}
