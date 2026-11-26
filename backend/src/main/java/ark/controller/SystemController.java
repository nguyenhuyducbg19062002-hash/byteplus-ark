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
 * Thao tác với máy đang chạy app (backend chỉ nghe 127.0.0.1): hộp thoại chọn thư mục của Windows / macOS (trình duyệt không
 * cho biết đường dẫn thật của thư mục) và mở thư mục bằng Explorer / Finder.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** Hộp thoại chọn thư mục. Trả về {path} hoặc {} nếu bấm Huỷ. */
    @PostMapping("/pick-folder")
    public Map<String, String> pickFolder(@RequestBody(required = false) Map<String, String> body) throws Exception {
        String initial = body == null ? null : body.get("initial");
        String title = body == null || body.get("title") == null ? "Chọn thư mục dự án" : body.get("title");
        if (MAC) return pickFolderMac(initial, title);
        if (!WINDOWS) throw new IllegalStateException("Máy này không có hộp chọn thư mục – dán đường dẫn thư mục vào ô");
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

    /** macOS: hộp chọn thư mục của Finder (osascript "choose folder"); bấm Huỷ → {}. */
    private Map<String, String> pickFolderMac(String initial, String title) throws Exception {
        StringBuilder as = new StringBuilder("POSIX path of (choose folder with prompt \"").append(asQuote(title)).append('"');
        if (initial != null && !initial.isBlank() && Files.isDirectory(Path.of(initial.trim()))) {
            as.append(" default location (POSIX file \"").append(asQuote(initial.trim())).append("\")");
        }
        as.append(')');
        Process proc = new ProcessBuilder("osascript", "-e", "activate", "-e", as.toString()).start();
        if (!proc.waitFor(15, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            return Map.of();
        }
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (proc.exitValue() != 0) {
            String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (err.contains("-128")) return Map.of(); // bấm Huỷ
            log.warn("Hộp chọn thư mục lỗi ({}): {}", proc.exitValue(), err);
            throw new IllegalStateException("Không mở được hộp chọn thư mục – dán đường dẫn thư mục vào ô");
        }
        String path = out.length() > 1 && out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
        return path.isEmpty() ? Map.of() : Map.of("path", path);
    }

    private static String asQuote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Mở thư mục bằng Explorer (Windows) / Finder (macOS). */
    @PostMapping("/open-folder")
    public ResponseEntity<Void> openFolder(@RequestBody Map<String, String> body) throws IOException {
        String p = body == null ? null : body.get("path");
        if (p == null || p.isBlank() || !Files.isDirectory(Path.of(p.trim()))) throw new IllegalArgumentException("Không có thư mục " + p);
        String dir = Path.of(p.trim()).toAbsolutePath().normalize().toString();
        if (WINDOWS) new ProcessBuilder("explorer.exe", dir).start();
        else if (MAC) new ProcessBuilder("open", dir).start();
        else if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(new java.io.File(dir));
        else throw new IllegalStateException("Máy này không mở được thư mục – mở bằng tay: " + dir);
        return ResponseEntity.noContent().build();
    }

    private static String psQuote(String s) {
        return s.replace("'", "''");
    }
}
