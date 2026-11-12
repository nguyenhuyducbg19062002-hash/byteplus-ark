package ark;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import ark.ui.AppFrame;

/** No arguments → desktop app. With arguments → command line (see Cli). */
public final class Main {
    public static void main(String[] args) {
        if (args.length > 0) {
            System.exit(Cli.run(args));
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // keep default look and feel
        }
        SwingUtilities.invokeLater(() -> new AppFrame().setVisible(true));
    }
}
