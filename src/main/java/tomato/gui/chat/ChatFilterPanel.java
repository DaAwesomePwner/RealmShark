package tomato.gui.chat;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** Editable local rules, with explicit scope and reversible changes. */
final class ChatFilterPanel extends JPanel {
    private final JCheckBox advertisements = new JCheckBox("Detect advertisements");
    private final JCheckBox whisperLinks = new JCheckBox("Ignore whisper links");
    private final JCheckBox gameIgnores = new JCheckBox("Use in-game ignores");
    private final JCheckBox inherited = new JCheckBox("Use existing spam rules");
    private final JTextArea players = editor("chat-ignored-players"), phrases = editor("chat-blocked-phrases"), allowed = editor("chat-allowed-players");

    ChatFilterPanel(ChatFilters filters, String observedStatus, Runnable saved, Runnable cancelled) {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel options = new JPanel(); options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        JTextArea introduction = ContentStyle.wrappingText("Matched messages go to Ignored and never trigger chat sounds.");
        introduction.setName("chat-filter-introduction"); options.add(introduction);
        ChatFilters.Settings settings = filters.settings();
        advertisements.setSelected(settings.advertisements); whisperLinks.setSelected(settings.whisperLinks);
        gameIgnores.setSelected(settings.gameIgnores); inherited.setSelected(settings.inheritedRules);
        advertisements.setName("chat-filter-advertisements"); whisperLinks.setName("chat-filter-links");
        gameIgnores.setName("chat-filter-game-ignores"); inherited.setName("chat-filter-inherited");
        options.add(option(advertisements, "Detect advertisements with links in PM and World messages."));
        options.add(option(whisperLinks, "Ignore every whisper containing a link."));
        options.add(option(gameIgnores, "Use observed in-game ignores when identity is available."));
        options.add(option(inherited, "Use existing block.txt and downloaded spam rules."));
        JTextArea status = ContentStyle.wrappingText(observedStatus + "\n" + filters.inheritedCount() + " existing spam rules loaded. Lists below use one entry per line.", 3);
        status.setName("chat-filter-observed-status");
        options.add(status);
        for (Component component : options.getComponents()) ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        players.setText(String.join("\n", settings.ignoredPlayers)); phrases.setText(String.join("\n", settings.phrases));
        allowed.setText(String.join("\n", settings.allowedPlayers));
        JTabbedPane lists = new JTabbedPane() {
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        lists.setName("chat-filter-lists"); lists.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        lists.addTab("Ignored players", list(players, "Exact player names, ignoring case. Applies to messages they send."));
        lists.addTab("Blocked phrases", list(phrases, "Literal text or domains, ignoring case. Blank lines are skipped."));
        lists.addTab("Allowed players", list(allowed, "Skips spam and link rules; explicit player ignores still apply."));
        JTextArea help = ContentStyle.wrappingText("Applies to retained and future messages. Own and System messages stay visible. Changes affect RealmShark only.", 2);
        help.setToolTipText("In-game ignore status is recorded when each message arrives.");
        JScrollPane page = ContentStyle.page(options, lists, help);
        page.setName("chat-filter-page"); add(page, BorderLayout.CENTER);
        JPanel actions = ContentStyle.controls();
        ((FlowLayout) actions.getLayout()).setAlignment(FlowLayout.TRAILING);
        JButton cancel = new JButton("Cancel"), save = new JButton("Save filters"); save.setName("chat-save-filters");
        cancel.setName("chat-cancel-filters");
        actions.add(cancel); actions.add(save); add(actions, BorderLayout.SOUTH);
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
        JTextArea label = ContentStyle.wrappingText(hint, 2);
        panel.add(label, BorderLayout.NORTH); panel.add(new JScrollPane(editor), BorderLayout.CENTER); return panel;
    }
    private static JPanel option(JCheckBox box, String description) {
        JPanel panel = new JPanel(new BorderLayout());
        JTextArea help = ContentStyle.wrappingText(description);
        help.setName(box.getName() + "-description");
        box.getAccessibleContext().setAccessibleDescription(description);
        panel.add(box, BorderLayout.NORTH); panel.add(help, BorderLayout.CENTER);
        return panel;
    }
    private static java.util.List<String> lines(JTextArea area) { return Arrays.asList(area.getText().split("\\R")); }
}
