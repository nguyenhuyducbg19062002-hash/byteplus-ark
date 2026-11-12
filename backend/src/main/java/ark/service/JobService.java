package ark.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import ark.dto.GeneratedFileDto;
import ark.dto.JobDto;
import ark.dto.PageResponse;
import ark.entity.JobRecord;
import ark.exception.GlobalExceptionHandler;
import ark.repository.GeneratedFileRepository;
import ark.repository.JobRecordRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs long work (generation, polling, asset upload) in the background. Live logs stay in memory while
 * a job runs; the final state + logs are persisted in the job table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {
    private final JobRecordRepository jobRepository;
    private final GeneratedFileRepository fileRepository;
    private final FileLibraryService fileLibrary;

    private final Map<String, JobContext> running = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    @FunctionalInterface
    public interface Work {
        void run(JobContext ctx) throws Exception;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedJobs() {
        int n = jobRepository.updateStatus(JobRecord.Status.RUNNING, JobRecord.Status.CANCELLED,
                "Backend khởi động lại khi job đang chạy");
        if (n > 0) log.info("Marked {} interrupted job(s) as cancelled", n);
    }

    public JobDto start(String type, String title, Work work) {
        JobRecord record = new JobRecord();
        record.setId(UUID.randomUUID().toString().substring(0, 8));
        record.setType(type);
        record.setTitle(title);
        record.setStatus(JobRecord.Status.RUNNING);
        record.setCreatedAt(Instant.now());
        jobRepository.save(record);

        JobContext ctx = new JobContext(record.getId(), type, title, record.getCreatedAt());
        running.put(ctx.getId(), ctx);
        pool.submit(() -> execute(ctx, work));
        return toDto(ctx);
    }

    private void execute(JobContext ctx, Work work) {
        try {
            work.run(ctx);
            ctx.setStatus(ctx.isCancelled() ? JobRecord.Status.CANCELLED : JobRecord.Status.SUCCEEDED);
            ctx.log(ctx.isCancelled() ? "Đã dừng." : "Hoàn thành.");
        } catch (Exception e) {
            ctx.setError(GlobalExceptionHandler.describe(e));
            ctx.setStatus(JobRecord.Status.FAILED);
            ctx.log("LỖI: " + ctx.getError());
        } finally {
            try {
                JobRecord r = jobRepository.findById(ctx.getId()).orElseThrow();
                r.setStatus(ctx.getStatus());
                r.setError(ctx.getError());
                r.setTaskId(ctx.getTaskId());
                r.setFileIds(ctx.getFileIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
                r.setLogs(String.join("\n", ctx.logs()));
                r.setFinishedAt(Instant.now());
                jobRepository.save(r);
            } catch (Exception e) {
                log.error("Could not persist job {}", ctx.getId(), e);
            } finally {
                running.remove(ctx.getId());
            }
        }
    }

    public JobDto get(String id) {
        JobContext ctx = running.get(id);
        if (ctx != null) return toDto(ctx);
        return jobRepository.findById(id).map(r -> toDto(r, true))
                .orElseThrow(() -> new NoSuchElementException("Không có job " + id));
    }

    public PageResponse<JobDto> list(int page, int size) {
        Page<JobRecord> p = jobRepository.findAll(PageRequest.of(Math.max(page, 1) - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<JobDto> items = p.getContent().stream()
                .map(r -> running.containsKey(r.getId()) ? toDto(running.get(r.getId()), false) : toDto(r, false))
                .toList();
        return new PageResponse<>(items, p.getTotalElements(), page, size);
    }

    public JobDto cancel(String id) {
        JobContext ctx = running.get(id);
        if (ctx == null) throw new IllegalStateException("Job " + id + " không còn chạy");
        ctx.cancel();
        return toDto(ctx);
    }

    private JobDto toDto(JobContext c) {
        return toDto(c, true);
    }

    private JobDto toDto(JobContext c, boolean withLogs) {
        return JobDto.builder().id(c.getId()).type(c.getType()).title(c.getTitle()).status(c.getStatus())
                .taskId(c.getTaskId()).error(c.getError()).createdAt(c.getCreatedAt())
                .logs(withLogs ? c.logs() : null).files(files(c.getFileIds())).build();
    }

    private JobDto toDto(JobRecord r, boolean withLogs) {
        List<Long> ids = r.getFileIds() == null || r.getFileIds().isBlank() ? List.of()
                : Arrays.stream(r.getFileIds().split(",")).map(Long::valueOf).toList();
        return JobDto.builder().id(r.getId()).type(r.getType()).title(r.getTitle()).status(r.getStatus())
                .taskId(r.getTaskId()).error(r.getError()).createdAt(r.getCreatedAt()).finishedAt(r.getFinishedAt())
                .logs(withLogs && r.getLogs() != null ? List.of(r.getLogs().split("\n")) : null)
                .files(files(ids)).build();
    }

    private List<GeneratedFileDto> files(List<Long> ids) {
        return ids.isEmpty() ? List.of() : fileLibrary.toDtos(fileRepository.findByIdIn(ids));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
