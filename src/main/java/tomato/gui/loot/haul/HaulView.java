package tomato.gui.loot.haul;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.*;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;

/**
 * One run's haul drawn the way the game shows loot. FULL (Loot › Explore): header, best drop, the bag shelf and the open group's
 * 8-slot grids. COMPACT (the run recap's Loot section): the shelf and the open group only. One shelf group is open at a time; a
 * group of several bags shows one grid per bag, newest first. Clicking an item, or Enter/Space on a focused one, reports its exact
 * variant key ({@link HaulModel#variantKey}); Full's "Open run" reports the run.
 */
public final class HaulView extends JPanel {
    public enum Mode { FULL, COMPACT }

    public static final String EMPTY = "No bags recorded in this run";
    static final int SLOTS_PER_ROW = 8, SLOT = 32, BAG = 32, HERO = 48, PORTAL = 40;
    private static final String INDEX = "loot-haul-index", OPEN = "loot-haul-open";

    private final Mode mode;
    private final Line header = new Line("loot-haul-header"), hero = new Line("loot-haul-hero"), shelf = new Line("loot-haul-shelf");
    private final Column grids = new Column("loot-haul-grids");
    private final KitText empty = KitText.caption(EMPTY);
    private final KitButton openRun = KitButton.ghost("Open run");
    private HaulModel model;
    private VisitRef run;
    private int open = -1;
    private Consumer<String> onOpenItem = key -> {};
    private Consumer<VisitRef> onOpenRun = ref -> {};

    public HaulView(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        setName("loot-haul");
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        empty.setName("loot-haul-empty");
        openRun.setName("loot-haul-open-run");
        openRun.addActionListener(e -> { if (run != null) onOpenRun.accept(run); });
        for (JComponent part : new JComponent[] {header, hero, shelf, grids, empty}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            part.setBorder(new EmptyBorder(0, 0, Tokens.S, 0));
            add(part);
        }
        show(HaulModel.of(null, List.of()), null);
    }

    /** Draws {@code model} and opens its default group; {@code run} is the exact run it belongs to (null: no "Open run"). */
    public void show(HaulModel model, VisitRef run) { show(model, run, null); }

    /** As {@link #show(HaulModel, VisitRef)}; {@code emptyReason} replaces {@link #EMPTY} when there are no bags (null or blank keeps it). */
    public void show(HaulModel model, VisitRef run, String emptyReason) {
        empty.setText(emptyReason == null || emptyReason.isBlank() ? EMPTY : emptyReason);
        this.model = Objects.requireNonNull(model, "model");
        this.run = run;
        boolean full = mode == Mode.FULL, any = !model.shelf().isEmpty();
        if (full) {
            fillHeader();
            fillHero();
        } else {
            header.removeAll();
            hero.removeAll();
        }
        header.setVisible(full && model.header() != null);
        hero.setVisible(full && model.hero() != null);
        empty.setVisible(!any);
        shelf.setVisible(any);
        grids.setVisible(any);
        fillShelf();
        openGroup(model.openByDefault());
        revalidate();
        repaint();
    }

    public HaulModel model() { return model; }

    /** The open shelf group's index; -1 when none is open. */
    public int openGroup() { return open; }

    /** Opens shelf group {@code index}: one 8-slot grid per bag, newest first. An index out of range closes every group. */
    public void openGroup(int index) {
        List<HaulModel.Shelf> groups = model.shelf();
        open = index >= 0 && index < groups.size() ? index : -1;
        for (Component tile : shelf.getComponents())
            if (tile instanceof AbstractButton button) button.setSelected(Integer.valueOf(open).equals(button.getClientProperty(INDEX)));
        grids.removeAll();
        if (open >= 0) for (HaulModel.Bag bag : groups.get(open).bags()) grids.add(grid(bag));
        grids.revalidate();
        grids.repaint();
    }

    /** Called with an item's exact variant key ("id/slots/applied") when it is clicked or activated. */
    public void onOpenItem(Consumer<String> action) { onOpenItem = Objects.requireNonNull(action, "action"); }

    /** Called with the run when Full's "Open run" is clicked. */
    public void onOpenRun(Consumer<VisitRef> action) { onOpenRun = Objects.requireNonNull(action, "action"); }

    private void fillHeader() {
        header.removeAll();
        HaulModel.Header h = model.header();
        if (h == null) return;
        header.add(new JLabel(Sprites.sprite(h.portalId(), PORTAL)));
        KitText title = KitText.emphasis(h.mapName());
        title.setName("loot-haul-title");
        KitText facts = KitText.caption(facts(h));
        facts.setName("loot-haul-facts");
        facts.setVisible(!facts.getText().isEmpty());
        header.add(KitLayouts.stack(0, title, facts));
        if (h.outcome() != null) {
            Chip outcome = new Chip(h.outcome(), h.outcomeTone() == null ? Tokens.Tone.NEUTRAL : h.outcomeTone());
            outcome.setName("loot-haul-outcome");
            header.add(outcome);
        }
        KitText tally = KitText.caption(model.tally());
        tally.setName("loot-haul-tally");
        tally.setVisible(!model.tally().isEmpty());
        header.add(tally);
        openRun.setVisible(run != null);
        header.add(openRun);
    }

    private void fillHero() {
        hero.removeAll();
        HaulModel.Hero best = model.hero();
        if (best == null) return;
        ItemSlot slot = slot(best.item(), HERO);
        slot.setName("loot-haul-hero-slot");
        hero.add(slot);
        KitText caption = KitText.caption("Best drop");
        KitText name = KitText.body(Sprites.name(best.item().id()));
        name.setName("loot-haul-hero-name");
        Line chips = new Line("loot-haul-hero-chips");
        String tier = tierLabel(best.item());
        if (!tier.isEmpty()) chips.add(new Chip(tier, Tokens.Tone.NEUTRAL));
        if (best.item().enchant().enchanted()) chips.add(new Chip(best.item().enchant().rarity().label, Tokens.Tone.ACCENT));
        KitText from = KitText.caption(from(best.bag(), best.dropper(), null));
        from.setName("loot-haul-hero-from");
        hero.add(KitLayouts.stack(0, caption, name, chips, from));
    }

    private void fillShelf() {
        shelf.removeAll();
        List<HaulModel.Shelf> groups = model.shelf();
        for (int i = 0; i < groups.size(); i++) {
            HaulModel.Shelf group = groups.get(i);
            int index = i;
            JToggleButton tile = new JToggleButton("×" + group.bags().size(), BagSprites.sprite(group.bag(), BAG));
            tile.setName("loot-haul-bag");
            tile.putClientProperty(INDEX, index);
            tile.setVerticalTextPosition(SwingConstants.BOTTOM);
            tile.setHorizontalTextPosition(SwingConstants.CENTER);
            tile.setContentAreaFilled(false);
            tile.setFont(Type.caption());
            tile.setBorder(new OpenBorder());
            String label = bagLabel(group.bag()) + ": " + count(group.bags().size(), "bag") + ", " + count(group.items(), "item");
            tile.setToolTipText(label);
            tile.getAccessibleContext().setAccessibleName(label);
            tile.addActionListener(e -> openGroup(index));
            bind(tile, tile::doClick, "ENTER");
            shelf.add(tile);
        }
    }

    private JComponent grid(HaulModel.Bag bag) {
        KitText caption = KitText.caption(from(bag.bag(), bag.dropper(), bag.time()));
        caption.setName("loot-haul-grid-caption");
        JPanel slots = new JPanel(new GridLayout(0, SLOTS_PER_ROW, Tokens.XS, Tokens.XS));
        slots.setName("loot-haul-grid");
        slots.setOpaque(false);
        int cells = Math.max(1, (bag.items().size() + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW) * SLOTS_PER_ROW;
        for (int i = 0; i < cells; i++) {
            if (i < bag.items().size()) { slots.add(slot(bag.items().get(i), SLOT)); continue; }
            ItemSlot blank = new ItemSlot(SLOT);
            blank.setEmpty();
            slots.add(blank);
        }
        Line holder = new Line(null);   // keeps the grid at its preferred width instead of stretching its cells
        holder.add(slots);
        Column block = new Column(null);
        for (JComponent part : new JComponent[] {caption, holder}) { part.setAlignmentX(LEFT_ALIGNMENT); block.add(part); }
        block.setBorder(new EmptyBorder(0, 0, Tokens.S, 0));
        return block;
    }

    private ItemSlot slot(LootFacts.Item item, int size) {
        ItemSlot slot = new ItemSlot(size);
        EnchantInfo enchant = item.enchant().state() == EnchantInfo.State.NOT_RECORDED ? null : item.enchant();
        slot.setItem(item.id(), tierLabel(item), enchant);
        String key = HaulModel.variantKey(item);
        slot.setFocusable(true);
        slot.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { onOpenItem.accept(key); }
        });
        bind(slot, () -> onOpenItem.accept(key), "ENTER", "SPACE");
        return slot;
    }

    /** "UT", "ST", the saved tier ("T12"), else the current definitions' label; "" when none is known. */
    static String tierLabel(LootFacts.Item item) {
        if (item.untiered()) return "UT";
        if (item.setTiered()) return "ST";
        if (item.tier() != null) return item.tier();
        return Objects.toString(ItemTiers.label(item.id()), "");
    }

    static String bagLabel(String bag) { return bag == null ? "Bag" : bag + " bag"; }

    /** "White bag · Marble Colossus · 21:52": the bag, then the dropper and time when known. */
    static String from(String bag, String dropper, Long time) {
        List<String> parts = new ArrayList<>();
        parts.add(bagLabel(bag));
        if (dropper != null && !dropper.isBlank()) parts.add(dropper);
        if (time != null && time > 0) parts.add(DisplayFormat.formatTimestamp(Instant.ofEpochMilli(time), DisplayFormat.TimestampMode.TIME));
        return String.join(" · ", parts);
    }

    /** "Wizard #3 · 9:40 PM · 00:14:00": the known facts only. */
    static String facts(HaulModel.Header h) {
        List<String> parts = new ArrayList<>();
        if (h.character() != null && !h.character().isBlank()) parts.add(h.character());
        if (h.entered() != null) parts.add(DisplayFormat.formatTimestamp(Instant.ofEpochMilli(h.entered()), DisplayFormat.TimestampMode.TIME));
        if (h.durationMs() != null) parts.add(DisplayFormat.formatDurationHMS(h.durationMs()));
        return String.join(" · ", parts);
    }

    private static String count(int n, String noun) { return n + " " + noun + (n == 1 ? "" : "s"); }

    private static void bind(JComponent target, Runnable action, String... keys) {
        for (String key : keys) target.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), OPEN);
        target.getActionMap().put(OPEN, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /** A left-to-right row only as tall as its content inside the haul's vertical stack. */
    private static final class Line extends JPanel {
        Line(String name) {
            super(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
            setName(name);
            setOpaque(false);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** A top-to-bottom stack only as tall as its content. */
    private static final class Column extends JPanel {
        Column(String name) {
            setName(name);
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** A bag tile's outline: accent and 2 px when its group is open, a subtle hairline otherwise; colors resolve while painting. */
    private static final class OpenBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component c) { return new Insets(Tokens.XS, Tokens.XS, Tokens.XS, Tokens.XS); }
        @Override public Insets getBorderInsets(Component c, Insets insets) { insets.set(Tokens.XS, Tokens.XS, Tokens.XS, Tokens.XS); return insets; }
        @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int width, int height) {
            boolean open = c instanceof AbstractButton button && button.isSelected();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Tokens.color(open ? Tokens.Role.ACCENT : Tokens.Role.BORDER_SUBTLE));
                g.setStroke(new BasicStroke(open ? 2f : 1f));
                g.drawRoundRect(x + 1, y + 1, width - 3, height - 3, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            } finally {
                g.dispose();
            }
        }
    }
}
