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
    private final ark.service.Api888Service api888;

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

    /** Lưu 888API {key?, imageHost?} (key để trống / còn dạng che = giữ key cũ); trả về số dư nếu đã có key. */
    @PutMapping("/api888")
    public java.util.Map<String, Object> api888Save(@RequestBody java.util.Map<String, String> body) throws Exception {
        api888.save(body.get("key"), body.get("imageHost"));
        return api888.configured() ? api888.status() : java.util.Map.of();
    }

    /** Số dư + model 888API key nhìn thấy. */
    @GetMapping("/api888/status")
    public java.util.Map<String, Object> api888Status() throws Exception {
        return api888.status();
    }

    /** Xoá API key 888API trên máy này. */
    @DeleteMapping("/api888")
    public org.springframework.http.ResponseEntity<Void> api888Clear() throws Exception {
        api888.clear();
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /** Danh sách model 888API app biết (giới hạn thời lượng / tỉ lệ / số ảnh). */
    /** Nơi để ảnh của 888API dùng được không (TOS: thử bucket thật). */
    @GetMapping("/api888/image-host-check")
    public java.util.Map<String, Object> api888ImageHostCheck(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean force) {
        return api888.checkImageHost(force);
    }

    @GetMapping("/api888/models")
    public java.util.List<ark.service.Api888Service.Model> api888Models() {
        return ark.service.Api888Service.MODELS;
    }

    /** Đăng xuất BOTSD: xoá token và mật khẩu đã lưu (giữ tên đăng nhập). */

    @DeleteMapping("/botsd")
    public org.springframework.http.ResponseEntity<Void> botsdLogout() throws Exception {
        botsd.logout();
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
