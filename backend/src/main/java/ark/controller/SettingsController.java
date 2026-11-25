package ark.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final ark.service.BotsdService botsd;

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

    /** Đăng nhập BOTSD {username, password} (để trống = dùng tài khoản đã lưu); trả về số dư + bảng giá. */
    @PostMapping("/botsd/login")
    public java.util.Map<String, Object> botsdLogin(@RequestBody(required = false) java.util.Map<String, String> body) throws Exception {
        String u = body == null ? null : body.get("username");
        String p = body == null ? null : body.get("password");
        botsd.login(u, p);
        return botsd.status();
    }

    /** Số dư Point + bảng giá BOTSD hiện hành. */
    @GetMapping("/botsd/status")
    public java.util.Map<String, Object> botsdStatus() throws Exception {
        return botsd.status();
    }

    /** Đăng xuất BOTSD: xoá token và mật khẩu đã lưu (giữ tên đăng nhập). */
    @DeleteMapping("/botsd")
    public org.springframework.http.ResponseEntity<Void> botsdLogout() throws Exception {
        botsd.logout();
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
