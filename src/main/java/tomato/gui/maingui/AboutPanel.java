package tomato.gui.maingui;

import realmshark.branding.AppIdentity;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;

/**
 * Product identity and upstream credits: logo, name, version, description, credits and license. The About dialog
 * ({@link TomatoPopupAbout}) and Settings › About show this same content. Transparent, so it sits on either surface;
 * the muted version line follows the theme.
 */
public final class AboutPanel extends JPanel {
    private final JLabel version = new JLabel(AppIdentity.version() + "  ·  Custom build");

    public AboutPanel() {
        super(new BorderLayout(0, 8));
        setName("about-panel");
        setOpaque(false);

        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        JLabel logo = new JLabel(AppIdentity.icon(80));
        logo.getAccessibleContext().setAccessibleName(AppIdentity.NAME + " logo");
        heading.add(logo, BorderLayout.WEST);
        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(AppIdentity.NAME);
        name.setName("about-name");
        ContentStyle.font(name, ContentStyle.emphasis(ContentStyle.body()).deriveFont(ContentStyle.body().getSize2D() * 18f / ContentStyle.FONT_SIZE));
        identity.add(name);
        identity.add(Box.createVerticalStrut(6));
        version.setName("about-version");
        ContentStyle.font(version, ContentStyle.metadata(ContentStyle.body()));
        identity.add(version);
        heading.add(identity, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);

        JPanel details = new JPanel();
        details.setOpaque(false);
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
        add(details, BorderLayout.CENTER);
        refreshColors();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (version != null) refreshColors();
    }

    private void refreshColors() { version.setForeground(ContentStyle.color("muted")); }

    private static JLabel text(String value) {
        JLabel label = new JLabel(value);
        ContentStyle.font(label, ContentStyle.body());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}
