package ark.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import ark.ArkClient;
import ark.Config;
import ark.Json;

/** API key / base URL / output folder. */
final class SettingsPanel extends JPanel {
    SettingsPanel(AppFrame app) {
        super(new BorderLayout());
        Config cfg = app.config;

        JPasswordField key = new JPasswordField(cfg.storedApiKey(), 40);
        char echo = key.getEchoChar();
        JCheckBox show = new JCheckBox("Hiện");
        show.addActionListener(e -> key.setEchoChar(show.isSelected() ? (char) 0 : echo));
        JPanel keyRow = new JPanel(new BorderLayout(4, 0));
        keyRow.add(key, BorderLayout.CENTER);
        keyRow.add(show, BorderLayout.EAST);

        JTextField baseUrl = new JTextField(cfg.baseUrl());
        JTextField outDir = new JTextField(cfg.outputDir().toAbsolutePath().toString());
        JButton browse = new JButton("Chọn…");
        browse.addActionListener(e -> {
            File d = Ui.chooseDir(this, outDir.getText());
            if (d != null) outDir.setText(d.getAbsolutePath());
        });
        JPanel outRow = new JPanel(new BorderLayout(4, 0));
        outRow.add(outDir, BorderLayout.CENTER);
        outRow.add(browse, BorderLayout.EAST);

        JTextField ak = new JTextField(cfg.storedAccessKey());
        JPasswordField sk = new JPasswordField(cfg.storedSecretKey());
        JTextField project = new JTextField(cfg.projectName());
        project.setToolTipText("Để trống = default. Phải trùng project của API key dùng để tạo video (vd. twen-customer).");

        JButton save = new JButton("Lưu");
        save.addActionListener(e -> {
            cfg.setApiKey(new String(key.getPassword()));
            cfg.setBaseUrl(baseUrl.getText());
            cfg.setOutputDir(outDir.getText());
            cfg.setAccessKey(ak.getText());
            cfg.setSecretKey(new String(sk.getPassword()));
            cfg.setProjectName(project.getText());
            try {
                cfg.save();
                app.log("Đã lưu cài đặt vào " + cfg.file());
                if (!cfg.accessKey().isEmpty() && !cfg.secretKey().isEmpty()) app.assetPanel.loadGroups();
            } catch (Exception ex) {
                app.log("LỖI lưu cài đặt: " + ex.getMessage());
            }
        });

        JButton test = new JButton("Kiểm tra kết nối");
        test.addActionListener(e -> {
            String k = new String(key.getPassword()).trim();
            String u = baseUrl.getText().trim();
            app.log("Đang kiểm tra API key…");
            new SwingWorker<Map<String, Object>, Void>() {
                @Override
                protected Map<String, Object> doInBackground() throws Exception {
                    return new ArkClient(u.isEmpty() ? Config.DEFAULT_BASE_URL : u, k.isEmpty() ? cfg.apiKey() : k)
                            .listVideoTasks(1, 1, null, null);
                }

                @Override
                protected void done() {
                    try {
                        Map<String, Object> r = get();
                        app.log("Kết nối OK – API key hợp lệ. Số task video hiện có: " + Json.str(r, "total"));
                    } catch (Exception ex) {
                        app.log("Kết nối thất bại: " + Ui.message(ex));
                    }
                }
            }.execute();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(save);
        buttons.add(test);

        Ui.Form form = new Ui.Form()
                .row("API key", keyRow)
                .row("", new JLabel("<html>Lấy key: console.byteplus.com › ModelArk › <b>API keys</b>. "
                        + "Nếu để trống sẽ dùng biến môi trường ARK_API_KEY.<br>"
                        + "Lưu tại: " + cfg.file() + " (ngoài thư mục project).</html>"))
                .row("Base URL", baseUrl)
                .row("Thư mục lưu", outRow)
                .full(new JLabel("<html><br><b>Virtual Portrait (thư viện asset)</b> – dùng Access Key / Secret Key, "
                        + "không dùng API key ở trên.<br>Lấy tại console.byteplus.com › IAM › <b>API access key</b> "
                        + "(nên tạo IAM user có quyền ark:*Asset*).</html>"))
                .row("Access Key ID", ak)
                .row("Secret Access Key", sk)
                .row("Project", project)
                .full(buttons)
                .row("", new JLabel("<html><br>Lưu ý: model Seedance 2.x cần được kích hoạt (activate) trong console "
                        + "và tài khoản có số dư &gt; 30 USD hoặc gói tài nguyên.<br>"
                        + "Ảnh/video kết quả chỉ có URL 24 giờ – app tự tải về thư mục lưu.</html>"))
                .end();
        add(form, BorderLayout.CENTER);
    }
}
