package ark.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ark.config.Config;
import ark.entity.GeneratedFile;
import ark.entity.ScriptProject;
import ark.repository.ScriptProjectRepository;

/** Ghép asset Virtual Portrait theo mã chỉ trong phạm vi dự án: dự án khác trùng mã không được dùng asset của nhau. */
class ProjectLibraryScopeTest {
    private static ScriptProject project(long id, String folder, String group) {
        ScriptProject p = new ScriptProject();
        p.setId(id);
        p.setName("P" + id);
        p.setFolder(folder);
        p.setVpGroupId(group);
        return p;
    }

    private static Map<String, Object> asset(String id, String name, String group) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("Id", id);
        a.put("Name", name);
        a.put("GroupId", group);
        return a;
    }

    @Test
    void assetsNeverCrossProjects() {
        ScriptProject a = project(1, "du_an_a", "g-a");
        ScriptProject b = project(2, "du_an_b", "g-b");
        ScriptProject c = project(3, "du_an_c", null);
        ScriptProjectRepository repo = mock(ScriptProjectRepository.class);
        when(repo.findAll()).thenReturn(List.of(a, b, c));
        FileLibraryService files = mock(FileLibraryService.class);
        GeneratedFile ownedByA = new GeneratedFile();
        ownedByA.setProjectId(1L);
        when(files.byAssetId(anyString())).thenReturn(List.of());
        when(files.byAssetId("asset-console")).thenReturn(List.of());
        when(files.byAssetId("asset-a-free")).thenReturn(List.of(ownedByA));
        AssetLibraryService svc = new AssetLibraryService(mock(Config.class), mock(SettingsService.class), files, mock(JobService.class), repo);

        List<Map<String, Object>> assets = List.of(
                asset("asset-a", "CHAR_X_01.png", "g-a"),
                asset("asset-b", "CHAR_X_01.png", "g-b"),
                asset("asset-c-prefixed", "du_an_c__CHAR_X_01.png", "g-shared"));

        assertEquals("asset-a", svc.findAssetFor(assets, "CHAR_X_01", a).get("Id"));
        assertEquals("asset-b", svc.findAssetFor(assets, "CHAR_X_01", b).get("Id"));
        // C không có group riêng: chỉ nhận asset tên kèm thư mục của C, không lấy asset trong group của A / B
        assertEquals("asset-c-prefixed", svc.findAssetFor(assets, "CHAR_X_01", c).get("Id"));
        assertNull(svc.findAssetFor(List.of(assets.get(0), assets.get(1)), "CHAR_X_01", c));

        // Asset tự tải lên console (không thuộc dự án nào) dùng được; asset gắn với file của dự án A thì C không dùng
        assertEquals("asset-console", svc.findAssetFor(List.of(asset("asset-console", "CHAR_Y_01.jpg", "g-console")), "CHAR_Y_01", c).get("Id"));
        assertNull(svc.findAssetFor(List.of(asset("asset-a-free", "CHAR_Z_01.jpg", "g-other")), "CHAR_Z_01", c));
        // Asset tên kèm thư mục của dự án khác thì không dùng
        assertNull(svc.findAssetFor(List.of(asset("asset-x", "du_an_a__CHAR_W_01.jpg", "g-shared")), "CHAR_W_01", c));
    }
}
