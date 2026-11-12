package ark.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.dto.JobDto;
import ark.dto.PageResponse;
import ark.integration.byteplus.ArkClient;
import ark.integration.byteplus.Json;
import ark.integration.byteplus.SavedOutput;
import ark.integration.byteplus.VideoGenerator;
import lombok.RequiredArgsConstructor;

/** Seedance tasks stored on BytePlus: list / get / download / cancel-delete. */
@Service
@RequiredArgsConstructor
public class VideoTaskService {
    private final Config config;
    private final JobService jobs;
    private final FileLibraryService fileLibrary;

    @SuppressWarnings("unchecked")
    public PageResponse<Map<String, Object>> list(int page, int size, String status) throws Exception {
        Map<String, Object> r = ArkClient.from(config).listVideoTasks(page, size, status, null);
        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) Json.list(r, "items");
        Long total = Json.lng(r, "total");
        return new PageResponse<>(items, total == null ? items.size() : total, page, size);
    }

    public Map<String, Object> get(String id) throws Exception {
        return ArkClient.from(config).getVideoTask(id);
    }

    public JobDto download(String id) {
        ArkClient c = ArkClient.from(config);
        return jobs.start("download", "Tải video " + id, ctx -> {
            ctx.setTaskId(id);
            Map<String, Object> task = c.getVideoTask(id);
            for (SavedOutput o : VideoGenerator.downloadOutputs(c, task, config.outputDir(), ctx::log)) {
                ctx.addFile(fileLibrary.record(o, Json.str(task, "model"), null, id, ctx.getId()));
            }
        });
    }

    /** queued → cancelled; finished → record deleted; running → error from the API. */
    public void delete(String id) throws Exception {
        ArkClient.from(config).deleteVideoTask(id);
    }
}
