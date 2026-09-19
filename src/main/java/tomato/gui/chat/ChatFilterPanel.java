package tomato.gui.chat;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import javax.swing.text.View;
import tomato.gui.modern.ContentStyle;

/** Editable local rules, with explicit scope and reversible changes. */
final class ChatFilterPanel extends JPanel {
    private final JCheckBox advertisements = new JCheckBox("Detect advertisements with links (PM and World)");
    private final JCheckBox whisperLinks = new JCheckBox("Ignore every whisper containing a link");
    private final JCheckBox gameIgnores = new JCheckBox("Use observed in-game ignores when identity is available");
    private final JCheckBox inherited = new JCheckBox("Use existing block.txt and downloaded spam rules");
    private final JTextArea players = editor("chat-ignored-players"), phrases = editor("chat-blocked-phrases"), allowed = editor("chat-allowed-players");

    ChatFilterPanel(ChatFilters filters, String observedStatus, Runnable saved, Runnable cancelled) {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel options = new JPanel(); options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(new JLabel("Matched messages go to Ignored and never trigger chat sounds."));
        ChatFilters.Settings settings = filters.settings();
        advertisements.setSelected(settings.advertisements); whisperLinks.setSelected(settings.whisperLinks);
        gameIgnores.setSelected(settings.gameIgnores); inherited.setSelected(settings.inheritedRules);
        advertisements.setName("chat-filter-advertisements"); whisperLinks.setName("chat-filter-links");
        gameIgnores.setName("chat-filter-game-ignores"); inherited.setName("chat-filter-inherited");
        options.add(advertisements); options.add(whisperLinks); options.add(gameIgnores); options.add(inherited);
        JTextArea status = note(observedStatus + "\n" + filters.inheritedCount() + " existing spam rules loaded. Lists below use one entry per line.", 3);
        options.add(status);
        for (Component component : options.getComponents()) ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        add(options, BorderLayout.NORTH);
        players.setText(String.join("\n", settings.ignoredPlayers)); phrases.setText(String.join("\n", settings.phrases));
        allowed.setText(String.join("\n", settings.allowedPlayers));
        JTabbedPane lists = new JTabbedPane();
        lists.addTab("Ignored players", list(players, "Exact player names, ignoring case. Applies to messages they send."));
        lists.addTab("Blocked phrases", list(phrases, "Literal text or domains, ignoring case. Blank lines are skipped."));
        lists.addTab("Allowed players", list(allowed, "Skips spam and link rules; explicit player ignores still apply."));
        add(lists, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        JTextArea help = note("Applies to retained and future messages. Own and System messages stay visible. Changes affect RealmShark only.", 2);
        help.setToolTipText("In-game ignore status is recorded when each message arrives.");
        footer.add(help, BorderLayout.CENTER);
        JPanel actions = ContentStyle.controls();
        ((FlowLayout) actions.getLayout()).setAlignment(FlowLayout.TRAILING);
        JButton cancel = new JButton("Cancel"), save = new JButton("Save filters"); save.setName("chat-save-filters");
        actions.add(cancel); actions.add(save); footer.add(actions, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        cancel.addActionListener(e -> cancelled.run());
        save.addActionListener(e -> {
            ChatFilters.Settings next = new ChatFilters.Settings();
            next.advertisements = advertisements.isSelected(); next.whisperLinks = whisperLinks.isSelected();
            next.gameIgnores = gameIgnores.isSelected(); next.inheritedRules = inherited.isSelected();
            next.ignoredPlayers = lines(players); next.phrases = lines(phrases); next.allowedPlayers = lines(allowed);
            filters.apply(next, true); saved.run();
        });
    }
    private static JTextArea editor(String name) {
        JTextArea area = new JTextArea(7, 28); area.setName(name);
        area.getAccessibleContext().setAccessibleName(name.replace("chat-", "").replace('-', ' '));
        area.setMargin(new Insets(4, 8, 4, 8)); return area;
    }
    private static JPanel list(JTextArea editor, String hint) {
        JPanel panel = new JPanel(new BorderLayout(0, 4)); panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        JTextArea label = note(hint, 2);
        panel.add(label, BorderLayout.NORTH); panel.add(new JScrollPane(editor), BorderLayout.CENTER); return panel;
    }
    private static JTextArea note(String text, int rows) {
        JTextArea area = new JTextArea(text, rows, 0) {
            @Override public Dimension getPreferredSize() {
                Insets padding = getInsets();
                int height = getFontMetrics(getFont()).getHeight() * getRows() + padding.top + padding.bottom;
                Container parent = getParent();
                if (parent != null) {
                    Insets border = parent.getInsets();
                    int width = parent.getWidth() - border.left - border.right - padding.left - padding.right;
                    if (width > 0) {
                        View view = getUI().getRootView(this);
                        view.setSize(width, Integer.MAX_VALUE);
                        height = Math.max(height, (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + padding.top + padding.bottom);
                    }
                }
                return new Dimension(0, height);
            }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        area.setEditable(false); area.setOpaque(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        ContentStyle.font(area, ContentStyle.metadata(ContentStyle.body()));
        return area;
    }
    private static java.util.List<String> lines(JTextArea area) { return Arrays.asList(area.getText().split("\\R")); }
}
