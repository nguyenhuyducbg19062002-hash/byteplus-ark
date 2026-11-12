package ark.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import ark.ArkClient;
import ark.Json;
import ark.VideoJob;

/** Lists video tasks (GET /contents/generations/tasks), downloads results, cancels / deletes tasks. */
final class TasksPanel extends JPanel {
    private static final String[] COLS = {"Task ID", "Model", "Trạng thái", "Tạo lúc", "Độ phân giải", "Tỉ lệ", "Thời lượng", "Tokens", "Lỗi"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AppFrame app;
    private final JComboBox<String> statusFilter = new JComboBox<>(
            new String[] {"(tất cả)", "queued", "running", "succeeded", "failed", "expired", "cancelled"});
    private final DefaultTableModel tableModel = new DefaultTableModel(COLS, 0) {
        @Override
        public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(tableModel);
    private final JLabel info = new JLabel(" ");
    private final List<Map<String, Object>> items = new ArrayList<>();

    TasksPanel(AppFrame app) {
        super(new BorderLayout());
        this.app = app;

        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> refresh());
        JButton download = new JButton("Tải video về");
        download.addActionListener(e -> download());
        JButton delete = new JButton("Huỷ / Xoá task");
        delete.addActionListener(e -> delete());
        JButton copyId = new JButton("Copy ID");
        copyId.addActionListener(e -> copy("id"));
        JButton copyUrl = new JButton("Copy URL video");
        copyUrl.addActionListener(e -> copy("url"));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        top.add(new JLabel("Lọc trạng thái:"));
        top.add(statusFilter);
        top.add(refresh);
        top.add(download);
        top.add(delete);
        top.add(copyId);
        top.add(copyUrl);
        top.add(info);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(8).setPreferredWidth(260);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) download();
            }
        });

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(new JLabel("  Nhấp đúp để tải video. URL video chỉ có hiệu lực 24 giờ; task ID lưu 7 ngày (draft 14 ngày)."), BorderLayout.SOUTH);
    }

    void refresh() {
        String st = statusFilter.getSelectedIndex() == 0 ? null : (String) statusFilter.getSelectedItem();
        info.setText("Đang tải…");
        new SwingWorker<Map<String, Object>, Void>() {
            @Override
            protected Map<String, Object> doInBackground() throws Exception {
                return app.client().listVideoTasks(1, 100, st, null);
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                try {
                    Map<String, Object> r = get();
                    items.clear();
                    tableModel.setRowCount(0);
                    for (Object it : Json.list(r, "items")) {
                        items.add((Map<String, Object>) it);
                        Long created = Json.lng(it, "created_at");
                        Object dur = Json.path(it, "duration") != null ? Json.path(it, "duration") + "s"
                                : (Json.path(it, "frames") != null ? Json.path(it, "frames") + " frames" : "");
                        String err = Json.str(it, "error", "message");
                        tableModel.addRow(new Object[] {
                                Json.str(it, "id"), Json.str(it, "model"), Json.str(it, "status"),
                                created == null ? "" : TIME.format(Instant.ofEpochSecond(created)),
                                nz(Json.str(it, "resolution")), nz(Json.str(it, "ratio")), dur,
                                nz(Json.str(it, "usage", "total_tokens")),
                                err == null ? "" : Json.str(it, "error", "code") + ": " + err});
                    }
                    info.setText("Tổng: " + nz(Json.str(r, "total")) + " (hiển thị " + items.size() + ")");
                } catch (Exception ex) {
                    info.setText("Lỗi");
                    app.log("LỖI tải danh sách task: " + Ui.message(ex));
                }
            }
        }.execute();
    }

    private Map<String, Object> selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            app.log("Chọn một task trong bảng trước.");
            return null;
        }
        String id = (String) tableModel.getValueAt(table.convertRowIndexToModel(row), 0);
        for (Map<String, Object> it : items) if (id.equals(Json.str(it, "id"))) return it;
        return null;
    }

    private void download() {
        Map<String, Object> it = selected();
        if (it == null) return;
        String id = Json.str(it, "id");
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                ArkClient c = app.client();
                return VideoJob.downloadOutputs(c, c.getVideoTask(id), app.outputDir(), app::log);
            }

            @Override
            protected void done() {
                try {
                    List<Path> files = get();
                    if (!files.isEmpty()) Ui.open(files.get(0), app);
                } catch (Exception ex) {
                    app.log("LỖI tải video: " + Ui.message(ex));
                }
            }
        }.execute();
    }

    private void delete() {
        Map<String, Object> it = selected();
        if (it == null) return;
        String id = Json.str(it, "id");
        String st = Json.str(it, "status");
        if ("running".equals(st)) {
            app.log("Task đang chạy (running) không thể huỷ – chỉ huỷ được task queued.");
            return;
        }
        String action = "queued".equals(st) ? "Huỷ task đang chờ" : "Xoá vĩnh viễn bản ghi task";
        if (JOptionPane.showConfirmDialog(this, action + "?\n" + id, "Xác nhận", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                app.client().deleteVideoTask(id);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    app.log(action + ": " + id + " – OK");
                    refresh();
                } catch (Exception ex) {
                    app.log("LỖI huỷ/xoá task: " + Ui.message(ex));
                }
            }
        }.execute();
    }

    private void copy(String what) {
        Map<String, Object> it = selected();
        if (it == null) return;
        String v = "id".equals(what) ? Json.str(it, "id") : Json.str(it, "content", "video_url");
        if (v == null) {
            app.log("Task chưa có URL video.");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(v), null);
        app.log("Đã copy: " + (v.length() > 80 ? v.substring(0, 80) + "…" : v));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
