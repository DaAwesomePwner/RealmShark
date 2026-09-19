package tomato.gui.maingui;

import realmshark.branding.AppIdentity;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/** Product identity and upstream credits, presented in an owned, modeless dialog. */
class TomatoPopupAbout {
    public JDialog addPopup(JFrame frame) {
        JDialog dialog = new JDialog(frame, "About " + AppIdentity.NAME, false);
        AppIdentity.apply(dialog);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        JPanel heading = new JPanel(new BorderLayout(8, 0));
        JLabel logo = new JLabel(AppIdentity.icon(80));
        logo.getAccessibleContext().setAccessibleName(AppIdentity.NAME + " logo");
        heading.add(logo, BorderLayout.WEST);
        JPanel identity = new JPanel();
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(AppIdentity.NAME);
        ContentStyle.font(name, ContentStyle.emphasis(ContentStyle.body()).deriveFont(ContentStyle.body().getSize2D() * 18f / ContentStyle.FONT_SIZE));
        identity.add(name);
        identity.add(Box.createVerticalStrut(6));
        JLabel version = new JLabel(AppIdentity.version() + "  ·  Custom build");
        ContentStyle.font(version, ContentStyle.metadata(ContentStyle.body()));
        version.setForeground(ContentStyle.color("muted"));
        identity.add(version);
        heading.add(identity, BorderLayout.CENTER);
        content.add(heading, BorderLayout.NORTH);

        JPanel details = new JPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.add(text("<html>A read-only companion for Realm of the Mad God.<br>"
            + "Explore combat, loot, chat, and session statistics<br>"
            + "from captured game traffic.</html>"));
        details.add(Box.createVerticalStrut(8));
        JLabel credits = text("Upstream credits");
        ContentStyle.font(credits, ContentStyle.metadata(ContentStyle.body()));
        details.add(credits);
        details.add(Box.createVerticalStrut(8));
        details.add(text("<html>Built on the RealmShark packet API.<br>"
            + "Original work by Anon and upstream contributors.</html>"));
        details.add(Box.createVerticalStrut(8));
        details.add(text("<html>Distributed under the MIT License.<br>"
            + "See LICENSE.md for the original copyright and license terms.</html>"));
        content.add(details, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(close);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return dialog;
    }

    private static JLabel text(String value) {
        JLabel label = new JLabel(value);
        ContentStyle.font(label, ContentStyle.body());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}
