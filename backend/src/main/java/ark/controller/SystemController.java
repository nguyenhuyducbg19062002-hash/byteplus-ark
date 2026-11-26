package ark.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

/**
 * Thao tác với máy đang chạy app (backend chỉ nghe 127.0.0.1): hộp thoại chọn thư mục của Windows (trình duyệt không cho
 * biết đường dẫn thật của thư mục) và mở thư mục bằng Explorer.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Hộp thoại chọn thư mục. Trả về {path} hoặc {} nếu bấm Huỷ. */
    @PostMapping("/pick-folder")
    public Map<String, String> pickFolder(@RequestBody(required = false) Map<String, String> body) throws Exception {
        if (!WINDOWS) throw new IllegalStateException("Hộp chọn thư mục chỉ có trên Windows – dán đường dẫn thư mục vào ô");
        String initial = body == null ? null : body.get("initial");
        String title = body == null || body.get("title") == null ? "Chọn thư mục dự án" : body.get("title");
        StringBuilder ps = new StringBuilder();
        ps.append("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n");
        ps.append("Add-Type -AssemblyName System.Windows.Forms\n");
        ps.append("$o = New-Object System.Windows.Forms.Form -Property @{TopMost=$true; ShowInTaskbar=$false; Width=1; Height=1; StartPosition='CenterScreen'}\n");
        ps.append("$o.Show(); $o.Activate()\n");
        ps.append("$d = New-Object System.Windows.Forms.FolderBrowserDialog\n");
        ps.append("$d.Description = '").append(psQuote(title)).append("'\n");
        ps.append("$d.ShowNewFolderButton = $false\n");
        if (initial != null && !initial.isBlank() && Files.isDirectory(Path.of(initial.trim()))) {
            ps.append("$d.SelectedPath = '").append(psQuote(initial.trim())).append("'\n");
        }
        ps.append("if ($d.ShowDialog($o) -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $d.SelectedPath }\n");
        ps.append("$o.Close()\n");
        String encoded = Base64.getEncoder().encodeToString(ps.toString().getBytes(StandardCharsets.UTF_16LE));
        Process proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded).redirectErrorStream(false).start();
        if (!proc.waitFor(15, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            return Map.of();
        }
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (proc.exitValue() != 0) {
            String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            log.warn("Hộp chọn thư mục lỗi ({}): {}", proc.exitValue(), err);
            throw new IllegalStateException("Không mở được hộp chọn thư mục – dán đường dẫn thư mục vào ô");
        }
        String path = out.lines().reduce((a, b) -> b).orElse("").trim();
        return path.isEmpty() ? Map.of() : Map.of("path", path);
    }

    /** Mở thư mục bằng Explorer. */
    @PostMapping("/open-folder")
    public ResponseEntity<Void> openFolder(@RequestBody Map<String, String> body) throws IOException {
        String p = body == null ? null : body.get("path");
        if (p == null || p.isBlank() || !Files.isDirectory(Path.of(p.trim()))) throw new IllegalArgumentException("Không có thư mục " + p);
        if (!WINDOWS) throw new IllegalStateException("Chỉ mở được thư mục trên Windows");
        new ProcessBuilder("explorer.exe", Path.of(p.trim()).toAbsolutePath().normalize().toString()).start();
        return ResponseEntity.noContent().build();
    }

    private static String psQuote(String s) {
        return s.replace("'", "''");
    }
}
