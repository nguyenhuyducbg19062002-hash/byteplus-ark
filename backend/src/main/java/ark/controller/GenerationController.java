package ark.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ark.dto.ImageGenerationRequest;
import ark.dto.JobDto;
import ark.dto.VideoGenerationRequest;
import ark.integration.byteplus.Models;
import ark.service.GenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Models + generation endpoints. Generation returns 202 with a job to poll at /api/v1/jobs/{id}. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GenerationController {
    private final GenerationService generationService;

    @GetMapping("/models")
    public Map<String, Object> models() {
        List<Map<String, String>> modes = new ArrayList<>();
        for (Models.VideoMode m : Models.VideoMode.values()) modes.add(Map.of("value", m.name(), "label", m.label));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("video", Models.VIDEO);
        m.put("image", Models.IMAGE);
        m.put("text", Models.TEXT);
        m.put("videoModes", modes);
        m.put("ratios", Models.RATIOS);
        return m;
    }

    @PostMapping("/images/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto generateImage(@Valid @RequestBody ImageGenerationRequest request) throws Exception {
        return generationService.generateImage(request);
    }

    @PostMapping("/videos/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto generateVideo(@Valid @RequestBody VideoGenerationRequest request) throws Exception {
        return generationService.generateVideo(request);
    }
}
