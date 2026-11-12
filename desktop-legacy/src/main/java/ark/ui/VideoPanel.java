package ark.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

import ark.ArkClient;
import ark.Media;
import ark.Models;
import ark.Models.VideoMode;
import ark.Models.VideoModel;
import ark.VideoJob;

/** Seedance video generation tab. */
final class VideoPanel extends JPanel {
    private final AppFrame app;
    private final JComboBox<VideoModel> model = new JComboBox<>(Models.VIDEO.toArray(new VideoModel[0]));
    private final JComboBox<VideoMode> mode = new JComboBox<>();
    private final JTextArea prompt = new JTextArea();

    private final JTextField firstFrame = new JTextField();
    private final JTextField lastFrame = new JTextField();
    private final JTextArea refImages = new JTextArea();
    private final JTextArea refVideos = new JTextArea();
    private final JTextArea refAudios = new JTextArea();
    private final JPanel firstPanel;
    private final JPanel lastPanel;
    private final JPanel omniPanel;

    private final JComboBox<String> resolution = new JComboBox<>();
    private final JComboBox<String> ratio = new JComboBox<>(Models.RATIOS.toArray(new String[0]));
    private final JSpinner duration = new JSpinner(new SpinnerNumberModel(5, -1, 30, 1));
    private final JLabel durationHint = new JLabel();
    private final JCheckBox audio = new JCheckBox("Tạo âm thanh (giọng nói, hiệu ứng, nhạc nền)", true);
    private final JCheckBox watermark = new JCheckBox("Gắn watermark");
    private final JTextField seed = new JTextField(10);
    private final JCheckBox cameraFixed = new JCheckBox("Cố định camera");
    private final JCheckBox lastFrameOut = new JCheckBox("Trả về ảnh khung cuối (để nối video tiếp theo)");
    private final JComboBox<String> outputFormat = new JComboBox<>(new String[] {"mp4", "mov"});

    private final JButton submit = new JButton("Tạo video");
    private final JButton stop = new JButton("Dừng theo dõi");
    private final JLabel status = new JLabel(" ");
    private volatile boolean stopRequested;
    private Path lastVideo;
    private final JButton openVideo = new JButton("Mở video");
    private final JButton pushLast = new JButton("Đẩy vào Virtual Portrait");
    private List<Path> lastFiles = List.of();

    VideoPanel(AppFrame app) {
        super(new BorderLayout());
        this.app = app;

        model.addActionListener(e -> onModelChanged());
        mode.addActionListener(e -> onModeChanged());

        firstPanel = section("Ảnh khung đầu (file hoặc URL)", fileRow(firstFrame));
        lastPanel = section("Ảnh khung cuối (file hoặc URL)", fileRow(lastFrame));

        Ui.Form omni = new Ui.Form()
                .row("Ảnh", listRow(refImages, "Ảnh", Ui.IMAGE_EXT))
                .row("Video (URL)", Ui.area(refVideos, 2))
                .row("Audio", listRow(refAudios, "Audio", Ui.AUDIO_EXT));
        omniPanel = section("Tham chiếu – mỗi dòng 1 file/URL (video chỉ nhận URL công khai hoặc asset://)", omni);

        JPanel inputs = new JPanel();
        inputs.setLayout(new BoxLayout(inputs, BoxLayout.Y_AXIS));
        inputs.add(firstPanel);
        inputs.add(lastPanel);
        inputs.add(omniPanel);

        JPanel durRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        durRow.add(duration);
        durRow.add(durationHint);

        JPanel seedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        seedRow.add(seed);
        seedRow.add(new JLabel("(để trống = ngẫu nhiên)"));
        seedRow.add(cameraFixed);

        submit.addActionListener(e -> run());
        stop.setEnabled(false);
        stop.addActionListener(e -> stopRequested = true);
        openVideo.setEnabled(false);
        openVideo.addActionListener(e -> { if (lastVideo != null) Ui.open(lastVideo, app); });
        JButton openDir = new JButton("Mở thư mục video");
        openDir.addActionListener(e -> {
            Path d = app.outputDir().resolve("videos");
            d.toFile().mkdirs();
            Ui.open(d, app);
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(submit);
        actions.add(stop);
        actions.add(openVideo);
        actions.add(pushLast);
        actions.add(openDir);
        pushLast.setEnabled(false);
        pushLast.addActionListener(e -> app.assetPanel.pushFiles(lastFiles));
        actions.add(status);

        Ui.Form form = new Ui.Form()
                .row("Model", model)
                .row("Chế độ", mode)
                .row("Prompt", Ui.area(prompt, 5))
                .full(inputs)
                .row("Độ phân giải", resolution)
                .row("Tỉ lệ khung", ratio)
                .row("Thời lượng (giây)", durRow)
                .row("", audio)
                .row("Seed", seedRow)
                .row("Định dạng", outputFormat)
                .row("", lastFrameOut)
                .row("", watermark)
                .full(actions)
                .end();

        JScrollPane scroll = new JScrollPane(form);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        onModelChanged();
    }

    private static JPanel section(String title, JPanel content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private JPanel fileRow(JTextField field) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        JButton b = new JButton("Chọn…");
        b.addActionListener(e -> {
            File[] f = Ui.chooseFiles(this, "Chọn ảnh", false, "Ảnh", Ui.IMAGE_EXT);
            if (f.length > 0) field.setText(f[0].getAbsolutePath());
        });
        p.add(field, BorderLayout.CENTER);
        p.add(b, BorderLayout.EAST);
        return p;
    }

    private JPanel listRow(JTextArea area, String what, String[] ext) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        JButton b = new JButton("Thêm…");
        b.addActionListener(e -> {
            for (File f : Ui.chooseFiles(this, "Chọn " + what, true, what, ext)) {
                area.append((area.getText().isEmpty() || area.getText().endsWith("\n") ? "" : "\n") + f.getAbsolutePath() + "\n");
            }
        });
        p.add(Ui.area(area, 2), BorderLayout.CENTER);
        p.add(b, BorderLayout.EAST);
        return p;
    }

    /**
     * Adds an asset (asset://ID or URL) as an omni reference. Switches to a Seedance 2.x model and
     * the reference mode if needed. assetType: Image / Video / Audio.
     */
    void addReference(String assetType, String ref) {
        VideoModel m = (VideoModel) model.getSelectedItem();
        if (m == null || !m.omni()) {
            for (int i = 0; i < model.getItemCount(); i++) {
                if (model.getItemAt(i).omni()) {
                    model.setSelectedIndex(i);
                    break;
                }
            }
        }
        mode.setSelectedItem(VideoMode.OMNI);
        JTextArea target = "Video".equals(assetType) ? refVideos : "Audio".equals(assetType) ? refAudios : refImages;
        String t = target.getText();
        target.append((t.isEmpty() || t.endsWith("\n") ? "" : "\n") + ref + "\n");
    }

    private void onModelChanged() {
        VideoModel m = (VideoModel) model.getSelectedItem();
        if (m == null) return;
        VideoMode prev = (VideoMode) mode.getSelectedItem();
        mode.removeAllItems();
        for (VideoMode vm : VideoMode.values()) if (m.supports(vm)) mode.addItem(vm);
        mode.setSelectedItem(prev != null && m.supports(prev) ? prev : VideoMode.TEXT);

        resolution.removeAllItems();
        for (String r : m.resolutions()) resolution.addItem(r);
        resolution.setSelectedItem(m.defaultResolution());

        duration.setModel(new SpinnerNumberModel(m.defaultDuration(), m.autoDuration() ? -1 : m.minDuration(), m.maxDuration(), 1));
        durationHint.setText(m.minDuration() + "–" + m.maxDuration() + " giây" + (m.autoDuration() ? ", -1 = model tự chọn" : ""));

        audio.setEnabled(m.audio());
        seed.setEnabled(m.seedAndCamera());
        cameraFixed.setEnabled(m.seedAndCamera());
        outputFormat.setEnabled(m.is25());
        if (!m.is25()) outputFormat.setSelectedItem("mp4");
        String refInfo = m.omni() ? " (tối đa " + m.maxRefImages() + " ảnh, " + m.maxRefVideos() + " video, " + m.maxRefAudios() + " audio)" : "";
        omniPanel.setToolTipText(refInfo);
        onModeChanged();
    }

    private void onModeChanged() {
        VideoMode vm = (VideoMode) mode.getSelectedItem();
        VideoModel m = (VideoModel) model.getSelectedItem();
        if (vm == null || m == null) return;
        firstPanel.setVisible(vm == VideoMode.FIRST_FRAME || vm == VideoMode.FIRST_LAST);
        lastPanel.setVisible(vm == VideoMode.FIRST_LAST);
        omniPanel.setVisible(vm == VideoMode.OMNI);
        boolean ratioLocked = m.is25() && (vm == VideoMode.FIRST_FRAME || vm == VideoMode.FIRST_LAST);
        if (ratioLocked) ratio.setSelectedItem("adaptive");
        if (m.id().startsWith("seedance-1-0") && vm == VideoMode.TEXT && "adaptive".equals(ratio.getSelectedItem())) {
            ratio.setSelectedItem("16:9");
        }
        ratio.setEnabled(!ratioLocked);
        revalidate();
        repaint();
    }

    private void run() {
        VideoJob.Params p = new VideoJob.Params();
        try {
            p.model = (VideoModel) model.getSelectedItem();
            p.mode = (VideoMode) mode.getSelectedItem();
            p.prompt = prompt.getText();
            p.firstFrame = firstFrame.getText().trim();
            p.lastFrame = lastFrame.getText().trim();
            p.refImages = Media.lines(refImages.getText());
            p.refVideos = Media.lines(refVideos.getText());
            p.refAudios = Media.lines(refAudios.getText());
            p.resolution = (String) resolution.getSelectedItem();
            p.ratio = (String) ratio.getSelectedItem();
            p.duration = (Integer) duration.getValue();
            p.generateAudio = audio.isSelected();
            p.watermark = watermark.isSelected();
            String s = seed.getText().trim();
            p.seed = s.isEmpty() ? null : Long.parseLong(s);
            p.cameraFixed = cameraFixed.isSelected();
            p.returnLastFrame = lastFrameOut.isSelected();
            p.outputFormat = (String) outputFormat.getSelectedItem();
        } catch (NumberFormatException ex) {
            app.log("LỖI: seed phải là số nguyên");
            return;
        }

        stopRequested = false;
        submit.setEnabled(false);
        stop.setEnabled(true);
        openVideo.setEnabled(false);
        status.setText("Đang gửi…");

        new SwingWorker<List<Path>, String>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                ArkClient c = app.client();
                String id = VideoJob.submit(c, p, app::log);
                publish("Đang chạy – task " + id);
                Map<String, Object> task = VideoJob.waitFor(c, id, msg -> {
                    app.log(msg);
                    publish(msg);
                }, () -> stopRequested);
                if (task == null) return null;
                return VideoJob.downloadOutputs(c, task, app.outputDir(), app::log);
            }

            @Override
            protected void process(List<String> chunks) {
                status.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                submit.setEnabled(true);
                stop.setEnabled(false);
                try {
                    List<Path> files = get();
                    if (files == null) {
                        status.setText("Đã dừng theo dõi");
                        return;
                    }
                    status.setText("Hoàn thành");
                    if (!files.isEmpty()) {
                        lastVideo = files.get(0);
                        openVideo.setEnabled(true);
                        lastFiles = files;
                        pushLast.setEnabled(true);
                        app.afterGenerated(files);
                    }
                } catch (Exception ex) {
                    status.setText("Lỗi");
                    app.log("LỖI tạo video: " + Ui.message(ex));
                }
            }
        }.execute();
    }
}
