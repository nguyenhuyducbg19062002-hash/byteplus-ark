package ark.ui;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Small Swing helpers. */
final class Ui {
    private Ui() {}

    static final String[] IMAGE_EXT = {"jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "gif", "heic", "heif"};
    static final String[] AUDIO_EXT = {"wav", "mp3"};

    private static final Preferences PREFS = Preferences.userNodeForPackage(Ui.class);

    /** Label / component grid. */
    static final class Form extends JPanel {
        private int row;

        Form() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        }

        Form row(String label, JComponent c) { return row(label, c, false); }

        Form row(String label, JComponent c, boolean grow) {
            GridBagConstraints l = new GridBagConstraints();
            l.gridx = 0;
            l.gridy = row;
            l.anchor = grow ? GridBagConstraints.NORTHWEST : GridBagConstraints.WEST;
            l.insets = new Insets(4, 2, 4, 8);
            add(new JLabel(label), l);
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 1;
            g.gridy = row;
            g.weightx = 1;
            g.weighty = grow ? 1 : 0;
            g.fill = grow ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(4, 0, 4, 2);
            add(c, g);
            row++;
            return this;
        }

        /** Component spanning both columns. */
        Form full(JComponent c) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = row++;
            g.gridwidth = 2;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(4, 0, 4, 0);
            add(c, g);
            return this;
        }

        /** Pushes rows to the top. */
        Form end() {
            GridBagConstraints g = new GridBagConstraints();
            g.gridy = row++;
            g.weighty = 1;
            add(new JLabel(), g);
            return this;
        }
    }

    static JScrollPane area(JTextArea a, int rows) {
        a.setRows(rows);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        return new JScrollPane(a);
    }

    static File[] chooseFiles(Component parent, String title, boolean multi, String desc, String... ext) {
        JFileChooser fc = new JFileChooser(PREFS.get("lastDir", System.getProperty("user.home")));
        fc.setDialogTitle(title);
        fc.setMultiSelectionEnabled(multi);
        if (ext.length > 0) fc.setFileFilter(new FileNameExtensionFilter(desc, ext));
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return new File[0];
        File[] files = multi ? fc.getSelectedFiles() : new File[] {fc.getSelectedFile()};
        if (files.length > 0 && files[0].getParentFile() != null) PREFS.put("lastDir", files[0].getParent());
        return files;
    }

    static File chooseDir(Component parent, String start) {
        JFileChooser fc = new JFileChooser(start);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }

    static void open(Path p, AppFrame app) {
        try {
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) {
            app.log("Không mở được " + p + ": " + e.getMessage());
        }
    }

    static String message(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.util.concurrent.ExecutionException || c instanceof RuntimeException) && c.getCause() != null
                && !(c instanceof IllegalArgumentException) && !(c instanceof IllegalStateException)) {
            c = c.getCause();
        }
        String m = c.toString();
        if (c instanceof ark.ArkException) return m;
        return c.getMessage() != null ? c.getMessage() : m;
    }
}
