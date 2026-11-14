package ark.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.mock.mockito.MockBean;

import ark.config.Config;
import ark.dto.ScriptProjectDto;
import ark.dto.ScriptRunClipsRequest;
import ark.entity.ScriptClip;
import ark.entity.ScriptProject;
import ark.repository.ScriptClipRepository;
import ark.repository.ScriptProjectRepository;

/**
 * Chạy clip / cảnh đã chọn không được kéo theo cả kịch bản. DB H2 trong bộ nhớ; bộ chạy video / ảnh là mock
 * nên không có gì được gửi lên BytePlus.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:selected-run;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ScriptSelectedRunTest {
    /** Config giả có sẵn từ lúc khởi động (FileLibraryService đọc outputDir khi app sẵn sàng). */
    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        Config testConfig() throws java.io.IOException {
            Config c = mock(Config.class);
            when(c.apiKey()).thenReturn("test-key");
            when(c.baseUrl()).thenReturn("http://127.0.0.1:9"); // cổng đóng – không gọi được BytePlus
            when(c.outputDir()).thenReturn(java.nio.file.Files.createTempDirectory("ark-test-out"));
            when(c.accessKey()).thenReturn("");
            when(c.secretKey()).thenReturn("");
            when(c.assetGroup()).thenReturn("");
            when(c.projectName()).thenReturn("");
            return c;
        }
    }

    @MockBean ScriptRunner runner;
    @MockBean ScriptImageRunner imageRunner;

    @Autowired ScriptService service;
    @Autowired ScriptProjectRepository projects;
    @Autowired ScriptClipRepository clips;

    Long projectId;

    @BeforeEach
    void setUp() {
        ScriptRunner.RefCheck check = mock(ScriptRunner.RefCheck.class); // mọi clip đủ ảnh
        when(runner.refCheck(any())).thenReturn(check);

        clips.deleteAll();
        projects.deleteAll();
        ScriptProject p = new ScriptProject();
        p.setName("Test");
        p.setFolder("test");
        p.setModel("dreamina-seedance-2-0-mini-260615");
        p.setResolution("720p");
        p.setRatio("16:9");
        projectId = projects.save(p).getId();
        clip("001", "CẢNH A", ScriptClip.Status.PENDING);
        clip("002", "CẢNH A", ScriptClip.Status.PENDING);
        clip("003", "CẢNH B", ScriptClip.Status.FAILED);
        clip("004", "CẢNH B", ScriptClip.Status.SUCCEEDED);
    }

    private void clip(String code, String scene, ScriptClip.Status status) {
        ScriptClip c = new ScriptClip();
        c.setProjectId(projectId);
        c.setCode(code);
        c.setSeq(Integer.parseInt(code));
        c.setSceneTitle(scene);
        c.setDuration(5);
        c.setPrompt("prompt " + code);
        c.setStatus(status);
        clips.save(c);
    }

    private ScriptClip get(String code) {
        return clips.findByProjectIdAndCode(projectId, code).orElseThrow();
    }

    private ScriptProject project() {
        return projects.findById(projectId).orElseThrow();
    }

    @Test
    void runOneClipRunsOnlyThatClip() {
        service.runClip(get("002").getId());
        assertEquals(ScriptProject.Status.RUNNING, project().getStatus());
        assertTrue(project().isOnlySelected(), "dự án phải ở chế độ chỉ chạy clip đã chọn");
        assertTrue(get("002").isRunSelected());
        assertFalse(get("001").isRunSelected(), "clip 001 không được chọn thì không được chạy");
    }

    @Test
    void runSceneSkipsDoneClipsUnlessRedoDone() {
        ScriptRunClipsRequest req = new ScriptRunClipsRequest();
        req.setScenes(List.of("CẢNH B"));
        ScriptProjectDto dto = service.runClips(projectId, req);
        assertTrue(dto.onlySelected());
        assertTrue(get("003").isRunSelected());
        assertEquals(ScriptClip.Status.PENDING, get("003").getStatus(), "clip lỗi được đặt lại để chạy");
        assertFalse(get("004").isRunSelected(), "clip đã xong bị bỏ qua khi không tick tạo lại");
        assertEquals(ScriptClip.Status.SUCCEEDED, get("004").getStatus());
        assertFalse(get("001").isRunSelected());

        req.setRedoDone(true);
        service.runClips(projectId, req);
        assertTrue(get("004").isRunSelected());
        assertEquals(ScriptClip.Status.PENDING, get("004").getStatus());
    }

    @Test
    void pauseKeepsSelectionAndAddsMore() {
        service.runClip(get("002").getId());
        service.pause(projectId);
        service.runClip(get("001").getId());
        assertTrue(get("001").isRunSelected());
        assertTrue(get("002").isRunSelected(), "tạm dừng rồi chọn thêm không làm mất clip đã chọn trước");
        assertEquals(2, service.get(projectId).selectedPending());

        service.pause(projectId);
        service.resumeSelected(projectId);
        assertEquals(ScriptProject.Status.RUNNING, project().getStatus());
        assertTrue(project().isOnlySelected());
    }

    @Test
    void runAllClearsSelection() {
        service.runClip(get("002").getId());
        service.start(projectId);
        assertFalse(project().isOnlySelected());
        assertFalse(get("002").isRunSelected());
    }

    @Test
    void runOneClipWhileRunningAllDoesNotSwitchMode() {
        service.start(projectId);
        service.runClip(get("003").getId());
        assertFalse(project().isOnlySelected(), "đang chạy cả kịch bản thì vẫn chạy cả kịch bản");
        assertEquals(ScriptClip.Status.PENDING, get("003").getStatus());
    }

    @Test
    void retryFailedRunsOnlyFailedClips() {
        service.retryFailed(projectId);
        assertTrue(project().isOnlySelected());
        assertTrue(get("003").isRunSelected());
        assertFalse(get("001").isRunSelected());
    }

    @Test
    void scenesListedInScriptOrderWithCounts() {
        List<Map<String, Object>> sc = service.scenes(projectId);
        assertEquals(2, sc.size());
        assertEquals("CẢNH A", sc.get(0).get("scene"));
        assertEquals(2, sc.get(1).get("total"));
        assertEquals(1, sc.get(1).get("done"));
        assertEquals(1, sc.get(1).get("failed"));
    }
}
