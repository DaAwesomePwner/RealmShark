package tomato.gui.settings;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.TomatoGUI;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.maingui.AboutPanel;
import tomato.gui.maingui.TomatoBandwidth;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.modern.ContentStyle;

/**
 * Settings › About (P6a): the About dialog's content ({@link AboutPanel}), then Info › Java version and Info › Net traffic as
 * buttons running the menu's own actions. Nothing here shows a path.
 */
public final class AboutSection extends JPanel {
    static final String DIAGNOSTICS_HELP = "Java version shows the Java runtime RealmShark runs on. Net traffic opens a window with "
        + "live packet traffic while capture runs.";

    private final JTextArea note = ContentStyle.wrappingText(DIAGNOSTICS_HELP);

    public AboutSection() { this(TomatoMenuBar::showJavaVersion, null); }

    /** @param netTraffic null opens the menu's Net traffic window over this page's frame */
    AboutSection(Runnable javaVersion, Runnable netTraffic) {
        super(new BorderLayout());
        setName("settings-about");
        setOpaque(false);
        JButton java = new JButton("Java version"), traffic = new JButton("Net traffic");
        java.setName("settings-about-java-version");
        java.setToolTipText(TomatoMenuBar.javaVersion());
        java.addActionListener(e -> javaVersion.run());
        traffic.setName("settings-about-net-traffic");
        traffic.setToolTipText("Live packet traffic while capture runs");
        traffic.addActionListener(e -> (netTraffic != null ? netTraffic : (Runnable) this::openNetTraffic).run());
        note.setName("settings-about-diagnostics-help");

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        AboutPanel about = new AboutPanel();
        about.setBorder(new EmptyBorder(Tokens.M, 0, Tokens.S, 0));
        body.add(fixedHeight(about));
        JPanel diagnostics = new JPanel(new BorderLayout(0, Tokens.XS));
        diagnostics.setOpaque(false);
        diagnostics.setBorder(new EmptyBorder(Tokens.M, 0, Tokens.S, 0));
        diagnostics.add(new SectionHeader("Diagnostics"), BorderLayout.NORTH);
        JPanel row = ContentStyle.controls();
        row.setOpaque(false);
        row.add(java);
        row.add(traffic);
        diagnostics.add(row, BorderLayout.CENTER);
        diagnostics.add(note, BorderLayout.SOUTH);
        body.add(fixedHeight(diagnostics));
        add(ContentStyle.page(null, body, null));
        refreshColors();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (note != null) refreshColors();
    }

    private void refreshColors() { note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED)); }

    /** The menu's Net traffic window, centered on the frame this page is in (the main window). */
    private void openNetTraffic() {
        Window window = SwingUtilities.getWindowAncestor(this);
        JFrame owner = window instanceof JFrame ? (JFrame) window : TomatoGUI.getFrame();
        if (owner != null) TomatoBandwidth.make(owner);
    }

    /** Left-aligned in the page and never stretched taller than it needs (as GeneralSection's groups). */
    private static JComponent fixedHeight(JComponent content) {
        JPanel wrapper = new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.add(content);
        return wrapper;
    }
}
