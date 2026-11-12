package ark.controller;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ark.desktop.DesktopShell;
import lombok.RequiredArgsConstructor;

/** Thông tin app + heartbeat của cửa sổ (bản desktop dùng để biết cửa sổ còn mở không). */
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
public class AppController {
    private final ObjectProvider<DesktopShell> shell;

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("app", "ark-studio", "desktop", shell.getIfAvailable() != null);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat() {
        shell.ifAvailable(DesktopShell::heartbeat);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/closing")
    public ResponseEntity<Void> closing() {
        shell.ifAvailable(DesktopShell::closing);
        return ResponseEntity.noContent().build();
    }
}
