package ark.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ark.entity.GeneratedFile;
import ark.entity.JobRecord;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/** Live state of a running background job (logs, produced files, cancel flag). */
@Slf4j
@Getter
public class JobContext {
    private final String id;
    private final String type;
    private final String title;
    private final Instant createdAt;
    private final List<Long> fileIds = new CopyOnWriteArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private volatile boolean cancelled;
    @Setter
    private volatile String taskId;
    @Setter
    private volatile JobRecord.Status status = JobRecord.Status.RUNNING;
    @Setter
    private volatile String error;

    JobContext(String id, String type, String title, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.createdAt = createdAt;
    }

    public void log(String msg) {
        synchronized (logLines) {
            logLines.add("[" + LocalTime.now().withNano(0) + "] " + msg);
            if (logLines.size() > 2000) logLines.remove(0);
        }
        log.info("[job {}] {}", id, msg);
    }

    public List<String> logs() {
        synchronized (logLines) {
            return new ArrayList<>(logLines);
        }
    }

    public void addFile(GeneratedFile f) {
        fileIds.add(f.getId());
    }

    public void cancel() {
        cancelled = true;
        log("Yêu cầu dừng…");
    }
}
