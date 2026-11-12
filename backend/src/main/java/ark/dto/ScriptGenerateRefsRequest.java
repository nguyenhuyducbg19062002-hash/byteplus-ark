package ark.dto;

import java.util.List;

import lombok.Data;

/** Tạo ảnh tham chiếu: codes rỗng = mọi mã đang được clip dùng mà chưa có ảnh; overwrite = tạo lại cả mã đã có ảnh. */
@Data
public class ScriptGenerateRefsRequest {
    private List<String> codes;
    private boolean overwrite;
}
