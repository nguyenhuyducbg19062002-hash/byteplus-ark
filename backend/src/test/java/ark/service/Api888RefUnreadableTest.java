package ark.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ark.integration.api888.Api888Exception;
import org.junit.jupiter.api.Test;

/** 888API không đọc được link ảnh: nhận đúng lỗi và đúng ảnh thứ mấy để đổi link mới. */
class Api888RefUnreadableTest {

    static final String MSG = "REFERENCE_UNREADABLE: 第 4 份参考图片：原素材地址未及时返回响应；请先重新上传该素材再提交，勿连续重试同一链接；未提交生成请求。";

    @Test
    void detectsUnreadableReference() {
        var e = new Api888Exception(400, "reference_prepare_failed", "reference_prepare_failed – " + MSG, null);
        assertTrue(ScriptRunner.refUnreadable(e));
        assertEquals(4, ScriptRunner.refIndex(e.getMessage()));
    }

    @Test
    void otherErrorsAreNotUnreadable() {
        assertFalse(ScriptRunner.refUnreadable(new Api888Exception(400, "invalid_ratio", "bad ratio", null)));
        assertEquals(2, ScriptRunner.refIndex("reference image 2 timed out"));
        assertNull(ScriptRunner.refIndex("không rõ ảnh nào"));
    }
}
