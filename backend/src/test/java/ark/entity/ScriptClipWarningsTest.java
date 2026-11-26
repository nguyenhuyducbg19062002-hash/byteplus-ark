package ark.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Cảnh báo "quá số ảnh của model" do bản cũ lưu cứng bị bỏ; lỗi dữ liệu thật vẫn giữ. */
class ScriptClipWarningsTest {

    @Test
    void dropsStoredModelLimitWarnings() {
        ScriptClip c = new ScriptClip();
        c.setWarnings("10 ảnh tham chiếu > tối đa 9 của Seedance 2.0\nMã CHAR-X không có trong sheet nhân vật / bối cảnh / đạo cụ");
        assertEquals(List.of("Mã CHAR-X không có trong sheet nhân vật / bối cảnh / đạo cụ"), c.warningList());
    }

    @Test
    void onlyModelLimitMeansNoWarning() {
        ScriptClip c = new ScriptClip();
        c.setWarnings("12 ảnh tham chiếu > tối đa 9 của Seedance 2.0 mini");
        assertEquals(List.of(), c.warningList());
        c.setWarnings(null);
        assertEquals(List.of(), c.warningList());
    }
}
