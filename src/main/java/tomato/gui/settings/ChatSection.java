package tomato.gui.settings;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.chat.ChatGUI;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.modern.ContentStyle;

/**
 * Settings › Chat (P6a). Save chat is Chat › Save Chat (the same preference and effect, through {@link TomatoMenuBar#setSaveChat},
 * so the menu and this checkbox always agree). Below it, the existing chat filter editor, embedded: it edits the live chat's own
 * rules and saves exactly as the Chat filters… dialog does. Clear Chat stays in the Chat menu and on the Chat page.
 * <p>
 * P6b (R3 B6): one scroll area. Saving, the Chat filters heading ({@code settings-chat-top}) and the editor's options and lists
 * (its embedded body, with no border or scroll of its own) scroll together in one page ({@code settings-chat-page}); the editor's
 * footer (the save status, Cancel and Save filters) is pinned below the page at the section's bottom edge, so it stays in view
 * however the page is scrolled ({@link Fill}). Polish B2's goal is kept: Save and Cancel are in view as the section opens.
 */
public final class ChatSection extends JPanel {
    static final String SAVE_HELP = "Also writes each chat message to a plain-text log in the app folder's chat folder, ignored "
        + "messages with their reason. It does not change saved session history. This is Edit › Chat › Save Chat.";
    static final String FILTERS_HELP = "The same rules as Chat › Actions › Chat filters…; saving here applies them to live chat at once.";

    private final JCheckBox save = new JCheckBox("Save chat");
    private final List<JTextArea> notes = new ArrayList<>();

    /**
     * @param filtersEditor builds the live chat's filter editor (production: ChatGUI::filtersEditor); called once, here. Its body goes
     *                      in the page and its footer is pinned below it; the editor refills both holders when it rebuilds.
     */
    public ChatSection(Supplier<ChatGUI.FiltersEditor> filtersEditor) {
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
        top.setName("settings-chat-top");
        ChatGUI.FiltersEditor editor = Objects.requireNonNull(filtersEditor.get(), "filtersEditor");
        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(top, BorderLayout.NORTH);
        content.add(editor.body(), BorderLayout.CENTER);
        JScrollPane page = ContentStyle.page(null, content, null);
        page.setName("settings-chat-page");
        setLayout(new Fill(page, editor.footer()));
        add(page);
        add(editor.footer());

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
     * The page over the pinned footer: the footer at its preferred height at the section's bottom edge, a small gap, and the page
     * filling the rest (it scrolls when its content is taller). The section never asks its host for more height than it is given
     * (its minimum is 0).
     */
    static final class Fill implements LayoutManager {
        private final JScrollPane page;
        private final JComponent footer;

        Fill(JScrollPane page, JComponent footer) { this.page = page; this.footer = footer; }

        @Override public void layoutContainer(Container section) {
            synchronized (section.getTreeLock()) {
                Insets insets = section.getInsets();
                int width = Math.max(0, section.getWidth() - insets.left - insets.right);
                int height = Math.max(0, section.getHeight() - insets.top - insets.bottom);
                int bottom = footer.isVisible() ? Math.min(height, footer.getPreferredSize().height) : 0;
                int gap = bottom > 0 ? Math.min(Tokens.S, height - bottom) : 0;
                page.setBounds(insets.left, insets.top, width, height - bottom - gap);
                footer.setBounds(insets.left, insets.top + height - bottom, width, bottom);
            }
        }

        @Override public Dimension preferredLayoutSize(Container section) {
            synchronized (section.getTreeLock()) {
                Insets insets = section.getInsets();
                Dimension footerSize = footer.isVisible() ? footer.getPreferredSize() : new Dimension();
                return new Dimension(Math.max(page.getPreferredSize().width, footerSize.width) + insets.left + insets.right,
                    page.getMinimumSize().height + (footerSize.height > 0 ? Tokens.S : 0) + footerSize.height + insets.top + insets.bottom);
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
