package ark.dto;

import lombok.Data;

/** Gán ảnh thư viện cho một mã tham chiếu (fileId = null để bỏ gán). */
@Data
public class ScriptReferenceUpdateRequest {
    private Long fileId;
}
