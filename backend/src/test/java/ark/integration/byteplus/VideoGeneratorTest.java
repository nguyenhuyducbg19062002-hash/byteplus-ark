package ark.integration.byteplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VideoGeneratorTest {
    private final List<String> logs = new ArrayList<>();

    private VideoGenerator.Params omni(String model, int images) {
        VideoGenerator.Params p = new VideoGenerator.Params();
        p.model = Models.video(model);
        p.mode = Models.VideoMode.OMNI;
        p.prompt = "Image 1 walks in";
        p.refImages = Collections.nCopies(images, "asset://asset-1");
        p.ratio = "16:9";
        p.duration = 5;
        return p;
    }

    @Test
    void omniBodyHasPromptThenReferenceImages() throws Exception {
        Map<String, Object> body = VideoGenerator.buildBody(omni("dreamina-seedance-2-0-fast-260128", 2), logs::add);
        List<?> content = (List<?>) body.get("content");
        assertEquals(3, content.size());
        assertEquals("text", ((Map<?, ?>) content.get(0)).get("type"));
        assertEquals("reference_image", ((Map<?, ?>) content.get(1)).get("role"));
        assertEquals(5, body.get("duration"));
        assertEquals("720p", body.get("resolution"));
        assertFalse(body.containsKey("seed"), "Seedance 2.x không nhận seed");
    }

    @Test
    void rejectsTooManyReferenceImages() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoGenerator.buildBody(omni("dreamina-seedance-2-0-fast-260128", 10), logs::add));
    }

    @Test
    void rejectsDurationOutOfRange() {
        VideoGenerator.Params p = omni("dreamina-seedance-2-0-fast-260128", 1);
        p.duration = 20;
        assertThrows(IllegalArgumentException.class, () -> VideoGenerator.buildBody(p, logs::add));
    }

    @Test
    void seedance25ImageToVideoForcesAdaptiveRatio() throws Exception {
        VideoGenerator.Params p = new VideoGenerator.Params();
        p.model = Models.video("dreamina-seedance-2-5-260628");
        p.mode = Models.VideoMode.FIRST_FRAME;
        p.firstFrame = "https://example.com/a.jpg";
        p.ratio = "9:16";
        Map<String, Object> body = VideoGenerator.buildBody(p, logs::add);
        assertEquals("adaptive", body.get("ratio"));
        assertTrue(logs.stream().anyMatch(l -> l.contains("adaptive")));
    }

    @Test
    void textModeNeedsPrompt() {
        VideoGenerator.Params p = new VideoGenerator.Params();
        p.model = Models.video("dreamina-seedance-2-0-260128");
        assertThrows(IllegalArgumentException.class, () -> VideoGenerator.buildBody(p, logs::add));
    }
}
