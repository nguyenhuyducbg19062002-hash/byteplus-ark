package ark.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ark.dto.ConnectionTestDto;
import ark.dto.SettingsDto;
import ark.dto.SettingsUpdateRequest;
import ark.service.SettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {
    private final SettingsService settingsService;

    @GetMapping
    public SettingsDto get() {
        return settingsService.get();
    }

    @PutMapping
    public SettingsDto update(@Valid @RequestBody SettingsUpdateRequest request) throws Exception {
        return settingsService.update(request);
    }

    @PostMapping("/connection-test")
    public ConnectionTestDto test() {
        return settingsService.testConnection();
    }
}
