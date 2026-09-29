package tomato.gui.settings;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootFilters;

/**
 * Settings › Loot filters (P6a): which bag colors are listed, the same {@link LootFilters} model and preferences as Edit › Filter
 * Loot. Every checkbox saves at once; each follows the model, so a change in the menu (or anywhere else) shows here too.
 */
public final class LootFiltersSection extends JPanel {
    static final String HELP = "Which bag colors show in Loot › Highlights' notable drops. Tiles and counts always include every "
        + "observed drop. Bag sounds are under Notifications.";

    private final LootFilters filters = LootFilters.get();
    private final Map<LootFilters.Kind, JCheckBox> boxes = new EnumMap<>(LootFilters.Kind.class);
    private final JButton showAll = new JButton("Show all");
    private final JTextArea note = ContentStyle.wrappingText(HELP);

    public LootFiltersSection() {
        super(new BorderLayout());
        setName("settings-loot-filters");
        setOpaque(false);
        // One column per checkbox width; the grid drops to fewer columns before a label would be cut at a large font.
        JPanel grid = ContentStyle.responsiveGrid(2, 160, Tokens.XS, true);
        grid.setOpaque(false);
        grid.setName("settings-loot-filters-kinds");
        for (LootFilters.Kind kind : LootFilters.Kind.values()) {
            JCheckBox box = new JCheckBox(kind.label());
            box.setName("settings-loot-filter-" + kind.name().toLowerCase(Locale.ROOT));
            box.setOpaque(false);
            box.addActionListener(e -> filters.set(kind, box.isSelected()));
            boxes.put(kind, box);
            grid.add(box);
        }
        showAll.setName("settings-loot-filters-show-all");
        showAll.addActionListener(e -> {
            for (LootFilters.Kind kind : LootFilters.Kind.values()) if (!filters.shows(kind)) filters.set(kind, true);
        });
        note.setName("settings-loot-filters-help");

        JPanel group = new JPanel(new BorderLayout(0, Tokens.XS));
        group.setOpaque(false);
        group.setBorder(new EmptyBorder(Tokens.M, 0, Tokens.S, 0));
        JPanel top = new JPanel(new BorderLayout(0, Tokens.XS));
        top.setOpaque(false);
        top.add(new SectionHeader("Loot filters"), BorderLayout.NORTH);
        top.add(note, BorderLayout.CENTER);
        group.add(top, BorderLayout.NORTH);
        group.add(grid, BorderLayout.CENTER);
        JPanel actions = ContentStyle.controls();
        actions.setOpaque(false);
        actions.add(showAll);
        group.add(actions, BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(group, BorderLayout.NORTH);
        add(ContentStyle.page(null, body, null));

        sync();
        filters.addListener(new Follow(this));
    }

    @Override public void updateUI() {
        super.updateUI();
        if (note != null) note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    /** Shows the model: one checkbox per color, and Show all only while a color is hidden. */
    private void sync() {
        boolean hidden = false;
        for (Map.Entry<LootFilters.Kind, JCheckBox> entry : boxes.entrySet()) {
            boolean shown = filters.shows(entry.getKey());
            entry.getValue().setSelected(shown);
            hidden |= !shown;
        }
        showAll.setEnabled(hidden);
        showAll.setToolTipText(hidden ? "Show every bag color again" : "Every bag color is shown");
        note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    /** Follows LootFilters (EDT) without keeping a discarded section alive: it unregisters once the section is collected. */
    private static final class Follow implements Runnable {
        private final WeakReference<LootFiltersSection> section;

        Follow(LootFiltersSection section) { this.section = new WeakReference<>(section); }

        @Override public void run() {
            LootFiltersSection current = section.get();
            if (current == null) LootFilters.get().removeListener(this); else current.sync();
        }
    }
}
