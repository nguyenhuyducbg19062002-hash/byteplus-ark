package ark.integration.byteplus;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import ark.config.Config;

/**
 * Client for the private Virtual Portrait asset library (GroupType AIGC), AK/SK signed.
 * Docs: docs/api/private-virtual-avatar-library-guide-preview.md, docs/api/*-asset*-api.md
 */
public final class AssetApi {
    private final OpenApiClient api;
    private final String project;

    public AssetApi(OpenApiClient api, String project) {
        this.api = api;
        this.project = project == null || project.isBlank() ? null : project.trim();
    }

    public static AssetApi from(Config c) {
        return new AssetApi(OpenApiClient.from(c), c.projectName());
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        if (project != null) m.put("ProjectName", project);
        return m;
    }

    /** First group requires signing the authorization letter in the console (My assets › Virtual Portrait). */
    public String createGroup(String name, String description) throws IOException, InterruptedException {
        return Json.str(api.call("CreateAssetGroup", body("Name", name, "Description", description, "GroupType", "AIGC")), "Id");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listGroups() throws IOException, InterruptedException {
        List<Map<String, Object>> out = new ArrayList<>();
        String token = null;
        do {
            Map<String, Object> r = api.call("ListAssetGroups", body("Filter", Map.of("GroupType", "AIGC"),
                    "MaxResults", 100, "NextToken", token, "SortBy", "CreateTime", "SortOrder", "Desc"));
            for (Object o : Json.list(r, "Items")) out.add((Map<String, Object>) o);
            token = Json.str(r, "NextToken");
        } while (token != null && !token.isBlank() && out.size() < 1000);
        return out;
    }

    /** CreateAsset is asynchronous: returns the asset ID; poll {@link #waitActive} before using it. */
    public String createAsset(String groupId, String url, String assetType, String name) throws IOException, InterruptedException {
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("Chưa chọn asset group");
        String n = name == null ? null : (name.length() > 64 ? name.substring(0, 64) : name);
        return Json.str(api.call("CreateAsset", body("GroupId", groupId, "URL", url, "AssetType", assetType, "Name", n)), "Id");
    }

    public Map<String, Object> getAsset(String id) throws IOException, InterruptedException {
        return api.call("GetAsset", body("Id", id));
    }

    /** One page (newest first), page-number pagination so the UI can show page numbers + total. */
    public Map<String, Object> listAssets(String groupId, String nameFilter, List<String> statuses, int page, int size)
            throws IOException, InterruptedException {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("GroupType", "AIGC");
        if (groupId != null && !groupId.isBlank()) filter.put("GroupIds", List.of(groupId));
        if (nameFilter != null && !nameFilter.isBlank()) filter.put("Name", nameFilter);
        if (statuses != null && !statuses.isEmpty()) filter.put("Statuses", statuses);
        return api.call("ListAssets", body("Filter", filter, "PageNumber", page, "PageSize", size,
                "SortBy", "CreateTime", "SortOrder", "Desc"));
    }

    public void deleteAsset(String id) throws IOException, InterruptedException {
        api.call("DeleteAsset", body("Id", id));
    }

    /** Polls GetAsset until Active / Failed. Returns the asset, or null if cancelled. */
    public Map<String, Object> waitActive(String id, Consumer<String> log, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String last = null;
        long t0 = System.currentTimeMillis();
        while (!cancelled.getAsBoolean()) {
            Map<String, Object> a = getAsset(id);
            String st = String.valueOf(Json.str(a, "Status"));
            if (!st.equals(last)) {
                log.accept(String.format("  asset %s: %s (%ds)", id, st, (System.currentTimeMillis() - t0) / 1000));
                last = st;
            }
            if ("Active".equals(st) || "Failed".equals(st)) return a;
            if (System.currentTimeMillis() - t0 > 30 * 60_000L) {
                throw new IOException("Asset " + id + " vẫn " + st + " sau 30 phút – thử lại sau");
            }
            Thread.sleep(4000);
        }
        return null;
    }

    public static String assetType(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".mov")) return "Video";
        if (n.endsWith(".wav") || n.endsWith(".mp3")) return "Audio";
        return "Image";
    }

    /** Library limits for images: aspect ratio (0.4, 2.5), width/height (300, 6000). */
    public static void checkImage(Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) return; // unknown format → let the server decide
        int w = img.getWidth();
        int h = img.getHeight();
        double ar = (double) w / h;
        if (w <= 300 || h <= 300 || w >= 6000 || h >= 6000 || ar <= 0.4 || ar >= 2.5) {
            throw new IllegalArgumentException(file.getFileName() + " (" + w + "x" + h + ") không đạt yêu cầu asset: "
                    + "cạnh 300–6000 px, tỉ lệ W/H 0.4–2.5");
        }
    }
}
