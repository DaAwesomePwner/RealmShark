package tomato.gui.glance.character;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.CharacterJournal;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Overview (spec §6.2). It shows base-versus-cap bars with the live "+N" boost, then the potions each stat still needs
 * (with vault counts only when known). Below those come the four equipped slots, this class's exalt summary and, for a dead
 * character, its death annotation. The full stat table, with field evidence, is an Analyst-only Collapsible. EDT only.
 */
final class OverviewTab extends JPanel {
    private static final String NEEDS_RULE = "Life and Mana take one potion per 5 points, other stats one per point. "
        + "Vault counts add the regular vault, potion storage and gift chest (a greater potion counts as two); seasonal characters show none.";
    /** A vault count older than this is dimmed as stale (spec §5.7). */
    static final long VAULT_STALE_MILLIS = 24 * 3_600_000L;
    private final StatBar[] bars = new StatBar[8];
    private final KitText[] values = new KitText[8], boosts = new KitText[8], tiers = new KitText[4];
    private final ItemSlot[] gear = new ItemSlot[4];
    private final JPanel needs = named(row(), "character-overview-needs");
    private final KitText exalts = named(KitText.body(""), "character-overview-exalts");
    private final KitText deathText = named(KitText.body(""), "character-overview-death-text");
    private final Card death;
    private final DefaultTableModel table = new DefaultTableModel(new String[]{"Stat", "Base", "Cap", "Potions to max", "Field evidence"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final Collapsible statTable;
    private final LongSupplier clock;
    private SheetModel shown;
    private String shownAge = "", shownDeathAge = "";
    private boolean shownStale;
    private static final Object NOT_SHOWN = new Object();
    /**
     * What each section last showed, so a section refills only when its own inputs changed (NOT_SHOWN until the first apply):
     * while playing, every rebuild stamps the build time into the identity, and refilling unchanged rows would reset the Analyst
     * table's selection and scroll and rebuild the needs row once a second.
     */
    private Object statsShown = NOT_SHOWN, gearShown = NOT_SHOWN, tableShown = NOT_SHOWN;

    /** {@code clock}: the sheet's clock (SheetContext.clock), which judges whether a vault count is stale. */
    OverviewTab(DisplayModeModel mode, LongSupplier clock) {
        super(new BorderLayout());
        this.clock = Objects.requireNonNull(clock, "clock");
        setOpaque(false);
        setName("character-overview");
        JPanel grid = ContentStyle.responsiveGrid(4, 150, Tokens.S);
        grid.setOpaque(false);
        for (int i = 0; i < 8; i++) {
            bars[i] = named(new StatBar(), "character-overview-bar-" + i);
            bars[i].getAccessibleContext().setAccessibleName(CharacterJournal.STATS[i]);
            values[i] = named(KitText.caption(""), "character-overview-value-" + i);
            boosts[i] = named(new KitText("", Type.caption(), Tokens.Role.ACCENT_TEXT), "character-overview-boost-" + i);
            JPanel numbers = clear(new FlowLayout(FlowLayout.TRAILING, Tokens.XS, 0), values[i], boosts[i]);
            grid.add(beside(bars[i], beside(numbers, KitText.caption(STATS[i]), BorderLayout.WEST, Tokens.XS), BorderLayout.NORTH, 2));
        }
        needs.setToolTipText(NEEDS_RULE);
        JPanel slots = clear(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
        for (int i = 0; i < 4; i++) {
            gear[i] = named(new ItemSlot(32), "character-overview-slot-" + i);
            tiers[i] = named(KitText.caption(SLOTS[i]), "character-overview-tier-" + i);
            tiers[i].setHorizontalAlignment(SwingConstants.CENTER);
            slots.add(beside(gear[i], tiers[i], BorderLayout.SOUTH, 2));
        }
        JPanel pair = ContentStyle.responsiveGrid(2, 260, Tokens.M);
        pair.setOpaque(false);
        pair.add(card(mode, "Gear", slots, "character-overview-gear"));
        pair.add(card(mode, "Class exalts", exalts, "character-overview-class-exalts"));
        death = card(mode, "Death", deathText, "character-overview-death");
        JTable rows = named(new JTable(table), "character-stat-rows");
        ContentStyle.table(rows, ContentStyle.Density.DENSE);
        rows.getAccessibleContext().setAccessibleName("Stat table: base, cap, potions to max and field evidence");
        statTable = named(new Collapsible("character-stat-table", "Stat table", ContentStyle.tableScroll(rows, 8), false), "character-stat-table");
        add(KitLayouts.stack(Tokens.M, card(mode, "Stats", KitLayouts.stack(Tokens.S, grid, needs), "character-overview-stats"), pair, death, statTable),
            BorderLayout.NORTH);
        // Raw provenance is diagnostic (spec §3.2): Analyst mode only.
        mode.bind(this, value -> statTable.setVisible(value == DisplayModeModel.Mode.ANALYST));
        apply(null);
    }

    /**
     * EDT. A null model (loading, or the character is not in the journal) clears everything. A model equal to the shown one is
     * skipped while the vault count's relative age, its staleness by the sheet's clock and the death age read the same (the
     * presenter re-applies once a second). Otherwise each section refills only when its own inputs changed: the bars and needs
     * when the stats (or the vault's age or staleness) did, the equipped slots when the gear did, the Analyst stat table when the
     * stats did. A rebuild that only moved the identity's times (every rebuild while playing) leaves them all as they are.
     */
    void apply(SheetModel model) {
        String age = vaultAge(model), deathAge = deathAge(model);
        boolean stale = vaultStale(model);
        if (model != null && model.equals(shown) && age.equals(shownAge) && deathAge.equals(shownDeathAge) && stale == shownStale) return;
        SheetModel.Stats stats = model == null ? null : model.stats();
        boolean playing = model != null && model.identity().playing();
        // Each "shown" value is recorded only after its section refilled, so an apply that failed midway is redone in full.
        List<Object> statsKey = Arrays.asList(stats, playing, age, stale);
        if (!statsKey.equals(statsShown)) {
            for (int i = 0; i < 8; i++) {
                Integer base = stats == null ? null : at(stats.base(), i), cap = stats == null ? null : at(stats.caps(), i);
                int boost = playing && base != null ? stats.boosts().get(i) : 0; // the boost describes the character in game right now
                bars[i].set(base, cap);
                values[i].setText(base == null ? DisplayFormat.UNAVAILABLE : cap == null ? String.valueOf(base) : base + "/" + cap);
                boosts[i].setText(boost > 0 ? "+" + boost : "");
                boosts[i].setVisible(boost > 0);
                boosts[i].setToolTipText(boost > 0 ? CharacterJournal.STATS[i] + " is " + (base + boost) + " right now with gear and effects" : null);
            }
            needs(stats, age, stale);
            statsShown = statsKey;
        }
        SheetModel.Gear equipped = model == null ? null : model.gear();
        if (!Objects.equals(equipped, gearShown)) {
            for (int i = 0; i < 4; i++) {
                int id = equipped == null ? -1 : equipped.slots().get(i);
                String tier = id > 0 ? equipped.tier(i) : ""; // computed off the EDT with the build's definitions
                if (id > 0) gear[i].setItem(id, tier); else if (id == 0) gear[i].setEmpty(); else gear[i].setUnknown();
                tiers[i].setText(tier.isEmpty() ? SLOTS[i] : tier);
            }
            gearShown = equipped;
        }
        SheetModel.Exalts classExalts = model == null ? null : model.exalts();
        boolean known = classExalts != null && classExalts.known();
        exalts.setText(known ? classExalts.summary() : DisplayFormat.UNAVAILABLE);
        exalts.setToolTipText(known ? "Exaltation tiers for this class; the Exalts tab lists every stat"
            : "Exalt progress arrives when capture reads your character list");
        SheetModel.Death dead = model == null ? null : model.death();
        death.setVisible(dead != null);
        if (dead != null) deathText.setText("Marked dead " + deathAge
            + (dead.occurredAt() == null ? "" : " · occurred " + DisplayFormat.formatTimestamp(dead.occurredAt()))
            + (dead.notes().isBlank() ? "" : " · " + dead.notes().strip().split("\\R", 2)[0]));
        // The Analyst table refills only for new stats: its rows keep their selection and scroll across identity-only rebuilds.
        if (!Objects.equals(stats, tableShown)) {
            table.setRowCount(0);
            if (stats != null) for (int i = 0; i < 8; i++) {
                Integer base = at(stats.base(), i), cap = at(stats.caps(), i);
                int need = stats.needed().get(i);
                table.addRow(new Object[]{CharacterJournal.STATS[i], base == null ? "Unknown" : base, cap == null ? "Unknown" : cap,
                    need < 0 ? "Unknown" : need == 0 ? "Maxed" : need, stats.evidence().get(i)});
            }
            tableShown = stats;
        }
        shown = model;
        shownAge = age;
        shownDeathAge = deathAge;
        shownStale = stale;
        revalidate();
        repaint();
    }

    private void needs(SheetModel.Stats stats, String age, boolean stale) {
        needs.removeAll();
        if (stats != null) {
            if (stats.maxed() == 8) needs.add(named(new KitText("All 8 stats maxed", Type.body(), Tokens.Role.GOOD), "character-overview-maxed"));
            for (int i = 0; i < stats.needs().size(); i++) needs.add(named(need(stats, i, age, stale), "character-overview-need-" + i));
            if (stats.unknown() > 0) needs.add(named(KitText.caption("Potions unknown for " + stats.unknown()
                + (stats.unknown() == 1 ? " stat" : " stats") + " (base stat or cap not captured)"), "character-overview-needs-unknown"));
        }
        needs.setVisible(needs.getComponentCount() > 0);
        needs.revalidate();
        needs.repaint();
    }

    /** The vault count's relative age as the needs show it; "" without a vault count. */
    private static String vaultAge(SheetModel model) {
        if (model == null || model.stats().vault() == null) return "";
        long at = model.stats().vaultObservedAt();
        return at > 0 ? KitFormat.relative(at) : "time unknown";
    }

    /** "Marked dead <ago>"'s relative age, so the presenter's once-a-second re-apply keeps it advancing; "" for a living character. */
    private static String deathAge(SheetModel model) {
        SheetModel.Death dead = model == null ? null : model.death();
        return dead == null ? "" : dead.markedAt() > 0 ? KitFormat.relative(dead.markedAt()) : "at an unknown time";
    }

    /**
     * Whether the model's vault count is stale by the sheet's clock: counted at an unknown time, or more than a day ago (spec
     * §5.7). False without a vault count.
     */
    private boolean vaultStale(SheetModel model) {
        if (model == null || model.stats().vault() == null) return false;
        long at = model.stats().vaultObservedAt();
        return at <= 0 || clock.getAsLong() - at > VAULT_STALE_MILLIS;
    }

    /**
     * "DEF needs 5 · 3 in vault (2 h ago)": a vault count shows its age ({@link #vaultAge}) and is dimmed as stale once a day
     * old by the sheet's clock ({@link #vaultStale}).
     */
    private static KitText need(SheetModel.Stats stats, int index, String age, boolean stale) {
        String line = stats.needs().get(index);
        if (stats.vault() == null) return KitText.body(line);
        long at = stats.vaultObservedAt();
        KitText text = new KitText(line + " (" + age + ")", Type.body(), stale ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT);
        text.setToolTipText((at > 0 ? "Vault counted " + DisplayFormat.formatTimestamp(at) : "When the vault was counted is unknown")
            + (stale ? "; open the vault with capture on to update it" : ""));
        return text;
    }
}
