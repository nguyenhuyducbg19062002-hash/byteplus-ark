package ark.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ark.dto.JobDto;
import ark.dto.PageResponse;
import ark.service.VideoTaskService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/api/v1/video-tasks")
@RequiredArgsConstructor
public class VideoTaskController {
    private final VideoTaskService videoTaskService;

    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") @Min(1) @Max(500) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                  @RequestParam(required = false) String status) throws Exception {
        return videoTaskService.list(page, size, status);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) throws Exception {
        return videoTaskService.get(id);
    }

    @PostMapping("/{id}/downloads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto download(@PathVariable String id) {
        return videoTaskService.download(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) throws Exception {
        videoTaskService.delete(id);
    }
}
