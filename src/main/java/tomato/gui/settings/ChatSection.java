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
 * <p>
 * Polish B2: the section no longer scrolls as a whole (that put the editor's Save and Cancel below the fold at 1240×800). Saving
 * and the Chat filters heading sit on top ({@code settings-chat-top}) and the editor fills the rest of the height, scrolling its
 * own options and lists, so its footer (Save filters, Cancel, the save status) stays at the bottom of the section, in view
 * ({@link Fill}).
 */
public final class ChatSection extends JPanel {
    static final String SAVE_HELP = "Also writes each chat message to a plain-text log in the app folder's chat folder, ignored "
        + "messages with their reason. It does not change saved session history. This is Edit › Chat › Save Chat.";
    static final String FILTERS_HELP = "The same rules as Chat › Actions › Chat filters…; saving here applies them to live chat at once.";
    /** The editor's room, in body lines, before the top gives way: its footer (about 3) and one filter list with its tabs (about 12). */
    static final int EDITOR_LINES = 16;

    private final JCheckBox save = new JCheckBox("Save chat");
    private final List<JTextArea> notes = new ArrayList<>();

    /** @param filtersEditor builds the live chat's filter editor (production: ChatGUI::filtersEditor); called once, here */
    public ChatSection(Supplier<? extends JComponent> filtersEditor) {
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
        // The top scrolls only when the section is too short for it and the editor (680×520 at font 18); otherwise it is whole.
        JScrollPane head = ContentStyle.page(null, top, null);
        head.setName("settings-chat-top");
        setLayout(new Fill(head, editor));
        add(head);
        add(editor);

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

    /**
     * The top (Saving and the Chat filters heading) at its whole height, the editor filling the rest of the section's height, so
     * the editor's own footer is the section's bottom edge. When the section is too short for the whole top and the editor's room
     * ({@value #EDITOR_LINES} body lines, or less if the editor needs less), the top gives way and scrolls, down to a third of the
     * height; the editor keeps the rest. The section never asks its host for more height than it is given (its minimum is 0).
     */
    static final class Fill implements LayoutManager {
        private final JScrollPane head;
        private final JComponent editor;

        Fill(JScrollPane head, JComponent editor) { this.head = head; this.editor = editor; }

        /** The top's height in a section of inner height {@code height}. */
        int headHeight(Container section, int height) {
            int whole = head.getMinimumSize().height;   // ContentStyle.page: the whole top at its width
            int room = Math.min(editor.getPreferredSize().height, EDITOR_LINES * section.getFontMetrics(ContentStyle.body()).getHeight());
            if (whole + room <= height) return whole;
            return Math.max(0, Math.min(whole, Math.max(height / 3, height - room)));
        }

        @Override public void layoutContainer(Container section) {
            synchronized (section.getTreeLock()) {
                Insets insets = section.getInsets();
                int width = Math.max(0, section.getWidth() - insets.left - insets.right);
                int height = Math.max(0, section.getHeight() - insets.top - insets.bottom);
                int top = headHeight(section, height);
                head.setBounds(insets.left, insets.top, width, top);
                editor.setBounds(insets.left, insets.top + top, width, height - top);
            }
        }

        @Override public Dimension preferredLayoutSize(Container section) {
            synchronized (section.getTreeLock()) {
                Insets insets = section.getInsets();
                Dimension editorSize = editor.getPreferredSize();
                return new Dimension(Math.max(head.getPreferredSize().width, editorSize.width) + insets.left + insets.right,
                    head.getMinimumSize().height + editorSize.height + insets.top + insets.bottom);
            }
        }

        @Override public Dimension minimumLayoutSize(Container section) {
            Insets insets = section.getInsets();
            return new Dimension(insets.left + insets.right, insets.top + insets.bottom);
        }

        @Override public void addLayoutComponent(String name, Component component) { }
        @Override public void removeLayoutComponent(Component component) { }
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
