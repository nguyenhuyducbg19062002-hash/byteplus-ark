package ark.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.time.LocalTime;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import ark.ArkClient;
import ark.Config;

/** Main window: tabs on top, shared log at the bottom. */
public final class AppFrame extends JFrame {
    final Config config = new Config();
    private final JTextArea logArea = new JTextArea();
    final JTabbedPane tabs = new JTabbedPane();
    TasksPanel tasksPanel;
    ImagePanel imagePanel;
    VideoPanel videoPanel;
    AssetPanel assetPanel;
    private final SettingsPanel settingsPanel;

    public AppFrame() {
        super("BytePlus Ark Studio – Seedream (ảnh) & Seedance (video)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        tasksPanel = new TasksPanel(this);
        imagePanel = new ImagePanel(this);
        videoPanel = new VideoPanel(this);
        assetPanel = new AssetPanel(this);
        settingsPanel = new SettingsPanel(this);
        tabs.addTab("Tạo ảnh (Seedream)", imagePanel);
        tabs.addTab("Tạo video (Seedance)", videoPanel);
        tabs.addTab("Virtual Portrait (asset)", assetPanel);
        tabs.addTab("Danh sách task video", tasksPanel);
        tabs.addTab("Cài đặt", settingsPanel);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, logScroll);
        split.setResizeWeight(0.78);
        getContentPane().add(split, BorderLayout.CENTER);

        setSize(1150, 860);
        setLocationRelativeTo(null);

        log("Base URL: " + config.baseUrl());
        log("Thư mục lưu kết quả: " + config.outputDir().toAbsolutePath());
        if (config.apiKey().isEmpty()) {
            tabs.setSelectedComponent(settingsPanel);
            log("Chưa có API key → nhập ở tab Cài đặt (lấy key tại console BytePlus › ModelArk › API keys).");
        }
        if (!config.accessKey().isEmpty() && !config.secretKey().isEmpty()) {
            assetPanel.loadGroups();
        }
    }

    /** Called by the image / video tabs after files are saved. */
    void afterGenerated(java.util.List<Path> files) {
        if (config.autoPush() && !files.isEmpty()) {
            log("Tự đẩy " + files.size() + " file vào Virtual Portrait…");
            assetPanel.pushFiles(files);
        }
    }

    /** Thread-safe log. */
    public void log(String msg) {
        String line = "[" + LocalTime.now().withNano(0) + "] " + msg + "\n";
        Runnable r = () -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    ArkClient client() {
        return ArkClient.from(config);
    }

    Path outputDir() {
        return config.outputDir();
    }
}
