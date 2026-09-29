package tomato.gui.settings;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.modern.ContentStyle;

/**
 * Settings › Chat (P6a). Save chat is Chat › Save Chat (the same preference and effect, through {@link TomatoMenuBar#setSaveChat},
 * so the menu and this checkbox always agree). Below it, the existing chat filter editor, embedded: it edits the live chat's own
 * rules and saves exactly as the Chat filters… dialog does. Clear Chat stays in the Chat menu and on the Chat page.
 */
public final class ChatSection extends JPanel {
    static final String SAVE_HELP = "Also writes each chat message to a plain-text log in the app folder's chat folder, ignored "
        + "messages with their reason. It does not change saved session history. This is Edit › Chat › Save Chat.";
    static final String FILTERS_HELP = "The same rules as Chat › Actions › Chat filters…; saving here applies them to live chat at once.";

    private final JCheckBox save = new JCheckBox("Save chat");
    private final List<JTextArea> notes = new ArrayList<>();

    /** @param filtersEditor builds the live chat's filter editor (production: ChatGUI::filtersEditor); called once, here */
    public ChatSection(Supplier<? extends JComponent> filtersEditor) {
        super(new BorderLayout());
        setName("settings-chat");
        setOpaque(false);
        save.setName("settings-chat-save");
        save.setOpaque(false);
        save.addActionListener(e -> TomatoMenuBar.setSaveChat(save.isSelected()));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(group("Saving", SAVE_HELP, "settings-chat-save-help", save));
        top.add(group("Chat filters", FILTERS_HELP, "settings-chat-filters-help"));
        JComponent editor = Objects.requireNonNull(filtersEditor.get(), "filtersEditor");
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(top, BorderLayout.NORTH);
        body.add(editor, BorderLayout.CENTER); // the editor takes the rest of the page and scrolls its own lists
        add(ContentStyle.page(null, body, null));

        sync();
        TomatoMenuBar.addSaveChatListener(new Follow(this));
    }

    @Override public void updateUI() {
        super.updateUI();
        if (notes != null) refreshColors();
    }

    private void sync() {
        save.setSelected(TomatoMenuBar.saveChat());
        refreshColors();
    }

    private void refreshColors() {
        for (JTextArea note : notes) note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    /** As GeneralSection's groups: a header, an optional control row and a muted help note. */
    private JComponent group(String title, String help, String helpName, JComponent... controls) {
        JPanel group = new JPanel(new BorderLayout(0, Tokens.XS)) {
            @Override public Dimension getMaximumSize() {
                Dimension size = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, size.height);
            }
        };
        group.setOpaque(false);
        group.setAlignmentX(LEFT_ALIGNMENT);
        group.setBorder(new EmptyBorder(Tokens.M, 0, Tokens.S, 0));
        group.add(new SectionHeader(title), BorderLayout.NORTH);
        JTextArea note = ContentStyle.wrappingText(help);
        note.setName(helpName);
        notes.add(note);
        if (controls.length == 0) {
            group.add(note, BorderLayout.CENTER);
        } else {
            JPanel row = ContentStyle.controls();
            row.setOpaque(false);
            for (JComponent control : controls) row.add(control);
            group.add(row, BorderLayout.CENTER);
            group.add(note, BorderLayout.SOUTH);
        }
        return group;
    }

    /** Follows Save Chat changes (EDT) without keeping a discarded section alive: it unregisters once the section is collected. */
    private static final class Follow implements Runnable {
        private final WeakReference<ChatSection> section;

        Follow(ChatSection section) { this.section = new WeakReference<>(section); }

        @Override public void run() {
            ChatSection current = section.get();
            if (current == null) TomatoMenuBar.removeSaveChatListener(this); else current.sync();
        }
    }
}
