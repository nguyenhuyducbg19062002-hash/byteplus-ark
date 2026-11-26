package ark.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

/** Nhận thư mục / file video trong thư mục dự án. */
class ProjectFolderNamesTest {
    private static String scene(String name) {
        Matcher m = ProjectFolderService.VIDEO_SCENE.matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    @Test
    void videoFileNamesGiveSceneNumber() {
        assertEquals("001", scene("001.mp4"));
        assertEquals("001", scene("001_(1).mp4"));
        assertEquals("012", scene("012_v2.mp4"));
        assertEquals("7", scene("7.MP4"));
        assertFalse(scene("cgt-20261111.mp4") != null);
        assertFalse(scene("001.mp4.part") != null);
    }

    @Test
    void folderNamesWithOrWithoutAccents() {
        assertTrue(ProjectFolderService.norm("Ảnh chờ duyệt").contains("cho duyet"));
        assertTrue(ProjectFolderService.norm("Anh tham chieu").contains("tham chieu"));
        assertTrue(ProjectFolderService.norm("Ảnh tham chiếu").contains("tham chieu"));
    }

    @Test
    void videoBelongsToClip() {
        assertTrue(ProjectFolderService.videoOfClip("001.mp4", "001"));
        assertTrue(ProjectFolderService.videoOfClip("001_(1).mp4", "001"));
        assertTrue(ProjectFolderService.videoOfClip("001_v2.mp4", "001"));
        assertTrue(ProjectFolderService.videoOfClip("1.mp4", "001"));
        assertFalse(ProjectFolderService.videoOfClip("010.mp4", "001"));
        assertTrue(ProjectFolderService.videoOfClip("019A_v2.mp4", "019A"));
        assertFalse(ProjectFolderService.videoOfClip("019AB.mp4", "019A"));
    }
}
