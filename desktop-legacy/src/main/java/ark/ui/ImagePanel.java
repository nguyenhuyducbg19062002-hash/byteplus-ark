package ark.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

import ark.ImageJob;
import ark.Media;
import ark.Models;
import ark.Models.ImageModel;

/** Seedream image generation tab. */
final class ImagePanel extends JPanel {
    private final AppFrame app;
    private final JComboBox<ImageModel> model = new JComboBox<>(Models.IMAGE.toArray(new ImageModel[0]));
    private final JTextArea prompt = new JTextArea();
    private final JTextArea refs = new JTextArea();
    private final JComboBox<String> size = new JComboBox<>();
    private final JCheckBox sequential = new JCheckBox("Tạo chuỗi ảnh liên quan (sequential)");
    private final JSpinner maxImages = new JSpinner(new SpinnerNumberModel(4, 1, 15, 1));
    private final JComboBox<String> format = new JComboBox<>(new String[] {"jpeg", "png"});
    private final JCheckBox watermark = new JCheckBox("Gắn watermark \"AI generated\"");
    private final JButton generate = new JButton("Tạo ảnh");
    private final JLabel status = new JLabel(" ");
    private final JPanel results = new JPanel(new java.awt.GridLayout(0, 2, 8, 8));
    private final JButton pushLast = new JButton("Đẩy vào Virtual Portrait");
    private List<Path> lastFiles = List.of();

    ImagePanel(AppFrame app) {
        super(new BorderLayout());
        this.app = app;

        size.setEditable(true);
        model.addActionListener(e -> onModelChanged());

        JButton addRef = new JButton("Thêm ảnh…");
        addRef.addActionListener(e -> {
            for (File f : Ui.chooseFiles(this, "Chọn ảnh tham chiếu", true, "Ảnh", Ui.IMAGE_EXT)) {
                refs.append((refs.getText().isEmpty() || refs.getText().endsWith("\n") ? "" : "\n") + f.getAbsolutePath() + "\n");
            }
        });
        JButton clearRef = new JButton("Xoá");
        clearRef.addActionListener(e -> refs.setText(""));
        JPanel refButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        refButtons.add(addRef);
        refButtons.add(clearRef);
        refButtons.add(new JLabel("Mỗi dòng 1 file hoặc URL. Để trống = text-to-image."));

        JPanel seqRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        seqRow.add(sequential);
        seqRow.add(new JLabel("  Tối đa:"));
        seqRow.add(maxImages);

        generate.addActionListener(e -> run());
        JButton openDir = new JButton("Mở thư mục ảnh");
        openDir.addActionListener(e -> {
            Path d = app.outputDir().resolve("images");
            d.toFile().mkdirs();
            Ui.open(d, app);
        });
        pushLast.setEnabled(false);
        pushLast.addActionListener(e -> app.assetPanel.pushFiles(lastFiles));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(generate);
        actions.add(pushLast);
        actions.add(openDir);
        actions.add(status);

        Ui.Form form = new Ui.Form()
                .row("Model", model)
                .row("Prompt", Ui.area(prompt, 6), true)
                .row("Ảnh tham chiếu", Ui.area(refs, 4))
                .row("", refButtons)
                .row("Kích thước", size)
                .row("", seqRow)
                .row("Định dạng", format)
                .row("", watermark)
                .full(actions);

        JPanel resultWrap = new JPanel(new BorderLayout());
        resultWrap.setBorder(BorderFactory.createTitledBorder("Kết quả (bấm vào ảnh để mở)"));
        resultWrap.add(results, BorderLayout.NORTH);
        JScrollPane resultScroll = new JScrollPane(resultWrap);
        resultScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, form, resultScroll);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);
        onModelChanged();
    }

    /** Adds a reference image (path / URL) – used by the Virtual Portrait tab. */
    void addRef(String ref) {
        String t = refs.getText();
        refs.append((t.isEmpty() || t.endsWith("\n") ? "" : "\n") + ref + "\n");
    }

    private void onModelChanged() {
        ImageModel m = (ImageModel) model.getSelectedItem();
        if (m == null) return;
        size.removeAllItems();
        for (String s : m.sizes()) size.addItem(s);
        size.setSelectedItem(m.defaultSize());
        sequential.setEnabled(m.sequential());
        maxImages.setEnabled(m.sequential());
        if (!m.sequential()) sequential.setSelected(false);
        format.setEnabled(m.outputFormat());
        size.setToolTipText("Chọn mức (1K/2K/4K…) hoặc gõ WxH, ví dụ 2048x1152");
    }

    private void run() {
        ImageJob.Params p = new ImageJob.Params();
        p.model = (ImageModel) model.getSelectedItem();
        p.prompt = prompt.getText();
        p.refs = Media.lines(refs.getText());
        Object s = size.getEditor().getItem();
        p.size = s == null ? null : s.toString();
        p.sequential = sequential.isSelected();
        p.maxImages = (Integer) maxImages.getValue();
        p.watermark = watermark.isSelected();
        p.outputFormat = format.isEnabled() ? (String) format.getSelectedItem() : null;

        generate.setEnabled(false);
        status.setText("Đang tạo ảnh…");
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                return ImageJob.run(app.client(), p, app.outputDir(), app::log);
            }

            @Override
            protected void done() {
                generate.setEnabled(true);
                try {
                    List<Path> files = get();
                    status.setText("Xong: " + files.size() + " ảnh");
                    showResults(files);
                    lastFiles = files;
                    pushLast.setEnabled(!files.isEmpty());
                    app.afterGenerated(files);
                } catch (Exception ex) {
                    String m = Ui.message(ex);
                    status.setText("Lỗi");
                    app.log("LỖI tạo ảnh: " + m);
                }
            }
        }.execute();
    }

    private void showResults(List<Path> files) {
        results.removeAll();
        for (Path f : files) {
            JLabel l = new JLabel(f.getFileName().toString());
            l.setVerticalTextPosition(JLabel.BOTTOM);
            l.setHorizontalTextPosition(JLabel.CENTER);
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            try {
                BufferedImage img = ImageIO.read(f.toFile());
                if (img != null) {
                    int w = 220;
                    int h = Math.max(1, img.getHeight() * w / img.getWidth());
                    l.setIcon(new ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH)));
                }
            } catch (Exception ignored) {
                // show file name only
            }
            l.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Ui.open(f, app);
                }
            });
            results.add(l);
        }
        results.revalidate();
        results.repaint();
    }
}
