package ark.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import ark.AssetService;
import ark.Json;
import ark.OutputIndex;

/**
 * Virtual Portrait (private AIGC asset library): push generated images/videos in, list them,
 * and use them back as references (asset://ID) for Seedance 2.x.
 */
final class AssetPanel extends JPanel {
    private static final String[] COLS = {"Asset ID", "Tên", "Loại", "Trạng thái", "Group", "Tạo lúc", "Dùng lần cuối", "Lỗi"};

    private record Group(String id, String name) {
        @Override
        public String toString() { return name + "  (" + id + ")"; }
    }

    private final AppFrame app;
    private final JComboBox<Group> groups = new JComboBox<>();
    private final JCheckBox autoPush = new JCheckBox("Tự đẩy mọi ảnh/video vừa tạo vào group này");
    private final JTextField nameFilter = new JTextField(14);
    private final DefaultTableModel model = new DefaultTableModel(COLS, 0) {
        @Override
        public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel info = new JLabel(" ");
    private final List<Map<String, Object>> items = new ArrayList<>();
    private String nextToken;
    private boolean loadingGroups;

    AssetPanel(AppFrame app) {
        super(new BorderLayout());
        this.app = app;

        autoPush.setSelected(app.config.autoPush());
        autoPush.addActionListener(e -> {
            app.config.setAutoPush(autoPush.isSelected());
            saveConfig();
        });
        groups.addActionListener(e -> {
            if (loadingGroups) return;
            Group g = (Group) groups.getSelectedItem();
            if (g != null && !g.id().equals(app.config.assetGroup())) {
                app.config.setAssetGroup(g.id());
                saveConfig();
                refresh(false);
            }
        });

        JButton loadGroups = button("Tải group", this::loadGroups);
        JButton newGroup = button("Tạo group mới…", this::createGroup);
        JButton refresh = button("Làm mới", () -> refresh(false));
        JButton more = button("Tải thêm", () -> refresh(true));
        JButton pushFiles = button("Đẩy file đã tạo…", this::pushChosenFiles);
        JButton pushUrl = button("Đẩy từ URL…", this::pushUrl);
        JButton useVideo = button("Dùng làm tham chiếu video", this::useInVideo);
        JButton useImage = button("Dùng làm ảnh tham chiếu (Seedream)", this::useInImage);
        JButton copy = button("Copy asset://", this::copyUri);
        JButton download = button("Tải về máy", this::download);
        JButton delete = button("Xoá asset", this::delete);

        JPanel r1 = flow();
        r1.add(new JLabel("Group:"));
        r1.add(groups);
        r1.add(loadGroups);
        r1.add(newGroup);
        r1.add(autoPush);
        JPanel r2 = flow();
        r2.add(new JLabel("Tìm theo tên:"));
        r2.add(nameFilter);
        r2.add(refresh);
        r2.add(more);
        r2.add(pushFiles);
        r2.add(pushUrl);
        r2.add(info);
        JPanel r3 = flow();
        r3.add(useVideo);
        r3.add(useImage);
        r3.add(copy);
        r3.add(download);
        r3.add(delete);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(r1);
        top.add(r2);
        top.add(r3);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(230);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(7).setPreferredWidth(220);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(new JLabel("<html>&nbsp;Quy trình: tạo ảnh/video → lưu về máy → đẩy URL gốc (còn hạn 24h) vào Virtual Portrait → chờ Active → "
                + "dùng asset://ID làm tham chiếu cho Seedance 2.x. Trong prompt gọi theo thứ tự: Image 1, Image 2, Video 1… "
                + "(không ghi asset ID vào prompt).</html>"), BorderLayout.SOUTH);
    }

    private static JPanel flow() {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private void saveConfig() {
        try {
            app.config.save();
        } catch (Exception ex) {
            app.log("LỖI lưu cài đặt: " + ex.getMessage());
        }
    }

    private AssetService service() {
        return AssetService.from(app.config);
    }

    // ---------- groups ----------

    void loadGroups() {
        info.setText("Đang tải group…");
        new SwingWorker<List<Map<String, Object>>, Void>() {
            @Override
            protected List<Map<String, Object>> doInBackground() throws Exception {
                return service().listGroups();
            }

            @Override
            protected void done() {
                try {
                    List<Map<String, Object>> list = get();
                    loadingGroups = true;
                    groups.removeAllItems();
                    Group selected = null;
                    for (Map<String, Object> g : list) {
                        Group item = new Group(Json.str(g, "Id"), String.valueOf(Json.str(g, "Name")));
                        groups.addItem(item);
                        if (item.id().equals(app.config.assetGroup())) selected = item;
                    }
                    loadingGroups = false;
                    if (selected != null) {
                        groups.setSelectedItem(selected);
                    } else if (groups.getItemCount() > 0) {
                        groups.setSelectedIndex(0);
                        app.config.setAssetGroup(((Group) groups.getSelectedItem()).id());
                        saveConfig();
                    }
                    info.setText(list.size() + " group");
                    if (list.isEmpty()) app.log("Chưa có asset group nào – bấm \"Tạo group mới\".");
                    refresh(false);
                } catch (Exception ex) {
                    loadingGroups = false;
                    info.setText("Lỗi");
                    app.log("LỖI tải asset group: " + Ui.message(ex));
                }
            }
        }.execute();
    }

    private void createGroup() {
        String name = JOptionPane.showInputDialog(this, "Tên group (tối đa 64 ký tự), ví dụ tên nhân vật:", "Tạo asset group",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return service().createGroup(name.trim(), "Created by BytePlus Ark Studio");
            }

            @Override
            protected void done() {
                try {
                    String id = get();
                    app.log("Đã tạo group " + name + ": " + id);
                    app.config.setAssetGroup(id);
                    saveConfig();
                    loadGroups();
                } catch (Exception ex) {
                    app.log("LỖI tạo group: " + Ui.message(ex)
                            + "\n   (Lần đầu tạo group phải ký thư uỷ quyền trong console: Model Playground › My assets › Virtual Portrait)");
                }
            }
        }.execute();
    }

    // ---------- list ----------

    @SuppressWarnings("unchecked")
    void refresh(boolean append) {
        String group = app.config.assetGroup();
        String filter = nameFilter.getText().trim();
        String token = append ? nextToken : null;
        if (append && token == null) {
            info.setText("Hết dữ liệu");
            return;
        }
        info.setText("Đang tải asset…");
        new SwingWorker<Map<String, Object>, Void>() {
            @Override
            protected Map<String, Object> doInBackground() throws Exception {
                return service().listAssets(group, filter, token, 100);
            }

            @Override
            protected void done() {
                try {
                    Map<String, Object> r = get();
                    if (!append) {
                        items.clear();
                        model.setRowCount(0);
                    }
                    for (Object o : Json.list(r, "Items")) {
                        Map<String, Object> a = (Map<String, Object>) o;
                        items.add(a);
                        String err = Json.str(a, "Error", "Message");
                        model.addRow(new Object[] {Json.str(a, "Id"), nz(Json.str(a, "Name")), Json.str(a, "AssetType"),
                                Json.str(a, "Status"), Json.str(a, "GroupId"), nz(Json.str(a, "CreateTime")),
                                nz(Json.str(a, "LastInferenceTime")), err == null ? "" : Json.str(a, "Error", "Code") + ": " + err});
                    }
                    nextToken = Json.str(r, "NextToken");
                    info.setText(items.size() + " asset" + (nextToken != null ? " (còn nữa)" : ""));
                } catch (Exception ex) {
                    info.setText("Lỗi");
                    app.log("LỖI tải asset: " + Ui.message(ex));
                }
            }
        }.execute();
    }

    private List<Map<String, Object>> selected() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            String id = (String) model.getValueAt(table.convertRowIndexToModel(row), 0);
            for (Map<String, Object> a : items) if (id.equals(Json.str(a, "Id"))) out.add(a);
        }
        if (out.isEmpty()) app.log("Chọn asset trong bảng trước (giữ Ctrl để chọn nhiều).");
        return out;
    }

    // ---------- push ----------

    /** Push saved output files (called by the image / video tabs after generation, or manually). */
    void pushFiles(List<Path> files) {
        String group = app.config.assetGroup();
        if (group.isEmpty()) {
            app.log("Chưa chọn asset group – vào tab Virtual Portrait, bấm \"Tải group\" hoặc \"Tạo group mới\".");
            return;
        }
        Path outDir = app.outputDir();
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                AssetService s = service();
                int ok = 0;
                for (Path f : files) {
                    try {
                        Map<String, Object> a = s.pushOutput(f, outDir, group, app::log, () -> false);
                        if (a != null && "Active".equals(Json.str(a, "Status"))) ok++;
                    } catch (Exception ex) {
                        app.log("LỖI đẩy " + f.getFileName() + ": " + Ui.message(ex));
                    }
                }
                return ok;
            }

            @Override
            protected void done() {
                try {
                    app.log("Virtual Portrait: " + get() + "/" + files.size() + " file đã Active.");
                } catch (Exception ex) {
                    app.log("LỖI đẩy asset: " + Ui.message(ex));
                }
                refresh(false);
            }
        }.execute();
    }

    private void pushChosenFiles() {
        File[] files = Ui.chooseFiles(this, "Chọn ảnh/video đã tạo (trong thư mục output, còn trong 24h)", true,
                "Ảnh / video / audio", "jpg", "jpeg", "png", "webp", "mp4", "mov", "wav", "mp3");
        if (files.length == 0) return;
        List<Path> paths = new ArrayList<>();
        for (File f : files) paths.add(f.toPath());
        pushFiles(paths);
    }

    private void pushUrl() {
        String url = JOptionPane.showInputDialog(this, "URL công khai của ảnh/video/audio (http/https):", "Đẩy từ URL",
                JOptionPane.PLAIN_MESSAGE);
        if (url == null || url.isBlank()) return;
        String group = app.config.assetGroup();
        String path = URI.create(url.trim()).getPath();
        String name = path == null ? "url-asset" : path.substring(path.lastIndexOf('/') + 1);
        String type = AssetService.assetType(Path.of(name.isEmpty() ? "x.jpg" : name));
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                AssetService s = service();
                String id = s.createAsset(group, url.trim(), type, name);
                app.log("Đã gửi " + type + " từ URL: " + id);
                s.waitActive(id, app::log, () -> false);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    app.log("LỖI đẩy từ URL: " + Ui.message(ex));
                }
                refresh(false);
            }
        }.execute();
    }

    // ---------- use back as references ----------

    private void useInVideo() {
        int n = 0;
        for (Map<String, Object> a : selected()) {
            if (!"Active".equals(Json.str(a, "Status"))) {
                app.log("Bỏ qua " + Json.str(a, "Id") + ": trạng thái " + Json.str(a, "Status") + " (phải Active)");
                continue;
            }
            app.videoPanel.addReference(Json.str(a, "AssetType"), "asset://" + Json.str(a, "Id"));
            n++;
        }
        if (n > 0) {
            app.log("Đã thêm " + n + " asset vào tab Video (chế độ tham chiếu). Trong prompt gọi Image 1, Image 2, Video 1…");
            app.tabs.setSelectedComponent(app.videoPanel);
        }
    }

    private void useInImage() {
        int n = 0;
        for (Map<String, Object> a : selected()) {
            if (!"Image".equals(Json.str(a, "AssetType")) || Json.str(a, "URL") == null) {
                app.log("Bỏ qua " + Json.str(a, "Id") + ": chỉ dùng được asset ảnh có URL");
                continue;
            }
            app.imagePanel.addRef(Json.str(a, "URL"));
            n++;
        }
        if (n > 0) {
            app.log("Đã thêm " + n + " ảnh vào tab Tạo ảnh (URL asset có hạn 12h – Seedream không nhận asset://).");
            app.tabs.setSelectedComponent(app.imagePanel);
        }
    }

    private void copyUri() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> a : selected()) sb.append("asset://").append(Json.str(a, "Id")).append('\n');
        if (sb.length() == 0) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString().trim()), null);
        app.log("Đã copy:\n" + sb.toString().trim());
    }

    private void download() {
        List<Map<String, Object>> sel = selected();
        if (sel.isEmpty()) return;
        Path dir = app.outputDir().resolve("assets");
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                Path last = null;
                for (Map<String, Object> a : sel) {
                    String url = Json.str(a, "URL");
                    if (url == null) continue;
                    String p = URI.create(url).getPath();
                    String ext = p != null && p.lastIndexOf('.') > p.lastIndexOf('/') ? p.substring(p.lastIndexOf('.')) : "";
                    Path target = dir.resolve(Json.str(a, "Id") + ext);
                    app.client().download(url, target);
                    OutputIndex.at(app.outputDir()).recordAsset(target, Json.str(a, "Id"));
                    app.log("Đã tải " + target);
                    last = target;
                }
                return last;
            }

            @Override
            protected void done() {
                try {
                    Path p = get();
                    if (p != null) Ui.open(p.getParent(), app);
                } catch (Exception ex) {
                    app.log("LỖI tải asset: " + Ui.message(ex));
                }
            }
        }.execute();
    }

    private void delete() {
        List<Map<String, Object>> sel = selected();
        if (sel.isEmpty()) return;
        if (JOptionPane.showConfirmDialog(this, "Xoá vĩnh viễn " + sel.size() + " asset? Không khôi phục được.", "Xác nhận",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                AssetService s = service();
                for (Map<String, Object> a : sel) {
                    s.deleteAsset(Json.str(a, "Id"));
                    app.log("Đã xoá asset " + Json.str(a, "Id"));
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    app.log("LỖI xoá asset: " + Ui.message(ex));
                }
                refresh(false);
            }
        }.execute();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
