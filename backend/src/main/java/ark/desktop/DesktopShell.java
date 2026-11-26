package ark.desktop;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ark.config.Config;
import ark.entity.ScriptClip;
import ark.entity.ScriptProject;
import ark.entity.ScriptReference;
import ark.repository.ScriptClipRepository;
import ark.repository.ScriptProjectRepository;
import ark.repository.ScriptReferenceRepository;
import ark.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Vỏ desktop: biểu tượng khay hệ thống + cửa sổ giao diện. Chỉ bật khi chạy bản app (-Dark.desktop=true).
 * <p>
 * Cửa sổ được coi là đã đóng khi trang ngừng gửi heartbeat quá 90 giây
 * (trang báo "closing" khi đóng thì chỉ chờ ~5 giây). Đóng cửa sổ khi đang có việc chạy (clip / ảnh / job) thì app
 * chạy nền ở khay và tự thoát khi xong; không có việc thì thoát luôn.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ark.desktop", havingValue = "true")
@RequiredArgsConstructor
public class DesktopShell {
    private static final long STALE_SECONDS = 90;

    private final ApplicationContext context;
    private final Config config;
    private final ScriptProjectRepository projects;
    private final ScriptClipRepository clips;
    private final ScriptReferenceRepository references;
    private final JobService jobs;

    private TrayIcon tray;
    private volatile Instant lastBeat;
    private volatile Instant lastBeatFromPage;
    private volatile boolean windowOpen;
    private volatile boolean quitWhenIdle;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        installTray();
        installMacHandlers();
        show();
    }

    /** macOS: bấm biểu tượng Ark Studio ở Dock khi app đang chạy → mở lại cửa sổ; Cmd+Q → thoát như menu Thoát. */
    private void installMacHandlers() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac") || !Desktop.isDesktopSupported()) return;
        try {
            Desktop d = Desktop.getDesktop();
            if (d.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                d.addAppEventListener((java.awt.desktop.AppReopenedListener) e -> new Thread(this::show, "ark-reopen").start());
            }
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler((e, response) -> {
                    response.cancelQuit();
                    new Thread(this::confirmQuit, "ark-quit").start();
                });
            }
        } catch (Exception e) {
            log.warn("macOS app events unavailable: {}", e.getMessage());
        }
    }

    // ---------- cửa sổ ----------

    /**
     * Mở cửa sổ. Không theo dõi tiến trình trình duyệt (Edge hay tự khởi động lại / chuyển cửa sổ sang tiến trình đang chạy
     * nên tiến trình vừa mở có thể thoát ngay) – cửa sổ còn mở hay không dựa vào heartbeat của trang.
     */
    public synchronized void show() {
        quitWhenIdle = false;
        windowOpen = true;
        lastBeat = Instant.now(); // cho trang thời gian tải
        DesktopLauncher.openWindow();
    }

    /** Trang gửi mỗi vài giây. */
    public void heartbeat() {
        if (lastBeatFromPage == null) log.info("Window connected (first heartbeat)");
        lastBeatFromPage = Instant.now();
        lastBeat = Instant.now();
        windowOpen = true;
        quitWhenIdle = false;
    }

    /** Trang báo đang đóng (pagehide) – nếu là tải lại trang thì heartbeat sẽ quay lại ngay. */
    public void closing() {
        lastBeat = Instant.now().minusSeconds(STALE_SECONDS - 5);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 30000)
    public void watch() {
        if (windowOpen && lastBeat != null && lastBeat.isBefore(Instant.now().minusSeconds(STALE_SECONDS))) {
            windowClosed();
        }
        if (quitWhenIdle && !windowOpen && busy().isEmpty()) {
            log.info("Background work finished – exiting");
            quit();
        }
    }

    private synchronized void windowClosed() {
        if (!windowOpen) return;
        windowOpen = false;
        log.info("Window closed (no heartbeat)");
        List<String> work = busy();
        if (work.isEmpty()) {
            quit();
            return;
        }
        quitWhenIdle = true;
        notify("Ark Studio vẫn chạy nền", String.join(", ", work) + ". App tự thoát khi xong – bấm biểu tượng để mở lại.");
    }

    // ---------- việc đang chạy ----------

    /** Mô tả các việc đang chạy (rỗng = rảnh). */
    public List<String> busy() {
        List<String> out = new ArrayList<>();
        try {
            int running = projects.findByStatus(ScriptProject.Status.RUNNING).size();
            long clipsGen = clips.countByStatus(ScriptClip.Status.GENERATING);
            int imgs = references.findByImageStatus(ScriptReference.ImageStatus.PENDING).size()
                    + references.findByImageStatus(ScriptReference.ImageStatus.GENERATING).size();
            int jobCount = jobs.runningCount();
            if (running > 0 || clipsGen > 0) out.add(running + " dự án đang chạy, " + clipsGen + " clip đang tạo");
            if (imgs > 0) out.add(imgs + " ảnh đang chờ / đang tạo");
            if (jobCount > 0) out.add(jobCount + " tác vụ nền");
        } catch (Exception e) {
            log.warn("Cannot check background work: {}", e.getMessage());
        }
        return out;
    }

    // ---------- khay hệ thống ----------

    private void installTray() {
        if (!SystemTray.isSupported()) return;
        try {
            Image img = Toolkit.getDefaultToolkit().getImage(getClass().getResource("/desktop/icon.png"));
            PopupMenu menu = new PopupMenu();
            menu.add(item("Mở Ark Studio", this::show));
            menu.add(item("Mở thư mục lưu", this::openOutputFolder));
            menu.addSeparator();
            menu.add(item("Thoát", this::confirmQuit));
            tray = new TrayIcon(img, "Ark Studio", menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> show()); // bấm đúp vào biểu tượng
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException e) {
            log.warn("System tray unavailable: {}", e.getMessage());
        }
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem m = new MenuItem(label);
        m.addActionListener(e -> new Thread(action, "ark-tray-action").start());
        return m;
    }

    private void openOutputFolder() {
        try {
            java.nio.file.Files.createDirectories(config.outputDir());
            Desktop.getDesktop().open(config.outputDir().toFile());
        } catch (Exception e) {
            notify("Không mở được thư mục", e.getMessage());
        }
    }

    private void confirmQuit() {
        List<String> work = busy();
        if (!work.isEmpty()) {
            int ok = JOptionPane.showConfirmDialog(null,
                    "Đang chạy: " + String.join(", ", work) + ".\n\nThoát bây giờ sẽ tạm dừng; lần mở sau bấm Chạy để tiếp tục "
                            + "(task video đã gửi không bị gửi lại).\n\nVẫn thoát?",
                    "Thoát Ark Studio", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
        }
        quit();
    }

    private void notify(String title, String msg) {
        if (tray != null) tray.displayMessage(title, msg, TrayIcon.MessageType.INFO);
    }

    private void quit() {
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        new Thread(() -> System.exit(SpringApplication.exit(context, () -> 0)), "ark-exit").start();
    }
}
