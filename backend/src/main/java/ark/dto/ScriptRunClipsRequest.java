package ark.dto;

import java.util.List;

import lombok.Data;

/**
 * Chạy chỉ các clip được chọn (không kéo theo cả kịch bản): theo id clip và / hoặc theo cảnh (sceneTitle).
 * redoDone = true thì clip đã xong cũng được tạo lại; false thì bỏ qua clip đã xong.
 */
@Data
public class ScriptRunClipsRequest {
    private List<Long> clipIds;
    private List<String> scenes;
    private boolean redoDone;
}
