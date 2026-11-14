package ark.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Các hàm thuần (không cần Spring / mạng) của bộ chạy kịch bản. */
class ScriptHelpersTest {

    @Test
    void outputModerationIsRetriable() {
        assertTrue(ScriptRunner.isOutputModeration("OutputVideoSensitiveContentDetected"));
        assertTrue(ScriptRunner.isOutputModeration("OutputAudioSensitiveContentDetected"));
        // bản quyền / giả mạo lặp lại y hệt → không thử lại
        assertFalse(ScriptRunner.isOutputModeration("OutputVideoSensitiveContentDetected.PolicyViolation"));
        assertFalse(ScriptRunner.isOutputModeration("InputImageSensitiveContentDetected.PrivacyInformation"));
        assertFalse(ScriptRunner.isOutputModeration("InputTextSensitiveContentDetected"));
        assertFalse(ScriptRunner.isOutputModeration(null));
    }

    @Test
    void moderationHintDependsOnAudioOrVideo() {
        assertTrue(ScriptRunner.outputModerationHint("OutputAudioSensitiveContentDetected").contains("âm thanh"));
        assertTrue(ScriptRunner.outputModerationHint("OutputVideoSensitiveContentDetected").contains("prompt"));
    }

    @Test
    void blockAnalyzerFindsCodeAndSkipsImageProblems() {
        assertEquals("OutputVideoSensitiveContentDetected", ScriptBlockAnalyzer.codeIn(
                "Bị kiểm duyệt chặn cả 3 lần: Task failed: OutputVideoSensitiveContentDetected – The request failed"));
        assertEquals("InputTextSensitiveContentDetected.PolicyViolation",
                ScriptBlockAnalyzer.codeIn("Bị từ chối: InputTextSensitiveContentDetected.PolicyViolation: copyright"));
        assertEquals(null, ScriptBlockAnalyzer.codeIn("Hết 3 lần thử: timeout"));
        assertTrue(ScriptBlockAnalyzer.isContentBlock("OutputAudioSensitiveContentDetected"));
        assertTrue(ScriptBlockAnalyzer.isContentBlock("InputTextSensitiveContentDetected"));
        // ảnh có mặt người thật: lỗi do ảnh, không do chữ → không phân tích prompt
        assertFalse(ScriptBlockAnalyzer.isContentBlock("InputImageSensitiveContentDetected.PrivacyInformation"));
        assertFalse(ScriptBlockAnalyzer.isContentBlock("RateLimitExceeded"));
    }

    @Test
    void missingAssetsParsesIdsOnce() {
        String msg = "The specified asset asset-20261111-ab12-mh7gc is not found; "
                + "the specified asset asset-20261111-cd34-xx9 is not found; asset asset-20261111-ab12-mh7gc is not found";
        assertEquals(List.of("asset-20261111-ab12-mh7gc", "asset-20261111-cd34-xx9"), ScriptRunner.missingAssets(msg));
        assertTrue(ScriptRunner.missingAssets(null).isEmpty());
        assertTrue(ScriptRunner.missingAssets("InvalidParameter").isEmpty());
    }

    @Test
    void promptCleanStripsThinkingMarkdownAndQuotes() {
        assertEquals("A woman in a red coat", ScriptPromptWriter.clean("<think>hmm</think>\n```text\nPrompt: \"A woman in a red coat\"\n```"));
        assertEquals("Plain prompt", ScriptPromptWriter.clean("Final Seedream prompt: Plain prompt"));
        assertEquals("", ScriptPromptWriter.clean(null));
    }

    @Test
    void fileNameMatchesCode() {
        String code = "CHAR_YVO_NAVYTEE_01";
        assertTrue(AssetLibraryService.nameMatchesCode("CHAR_YVO_NAVYTEE_01.png", code));
        assertTrue(AssetLibraryService.nameMatchesCode("char_yvo_navytee_01_v2.jpg", code));
        assertTrue(AssetLibraryService.nameMatchesCode("CHAR_YVO_NAVYTEE_01 final.jpg", code));
        assertFalse(AssetLibraryService.nameMatchesCode("CHAR_YVO_NAVYTEE_012.png", code));
        assertFalse(AssetLibraryService.nameMatchesCode("CHAR_YVO_NAVYTEE_01_back.png", code));
        assertFalse(AssetLibraryService.nameMatchesCode(null, code));
    }
}
