package ark.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ark.dto.JobDto;
import ark.dto.PageResponse;
import ark.service.JobService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {
    private final JobService jobService;

    @GetMapping
    public PageResponse<JobDto> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return jobService.list(page, size);
    }

    @GetMapping("/{id}")
    public JobDto get(@PathVariable String id) {
        return jobService.get(id);
    }

    /** Stops waiting (video / asset polling). The task keeps running on BytePlus. */
    @PostMapping("/{id}/cancel")
    public JobDto cancel(@PathVariable String id) {
        return jobService.cancel(id);
    }
}
