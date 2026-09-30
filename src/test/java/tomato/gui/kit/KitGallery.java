package tomato.gui.kit;

import java.awt.*;
import java.util.Arrays;
import java.util.Collections;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.EnchantInfo;

/** Every kit component with representative synthetic values, for visual review. */
final class KitGallery {
    private KitGallery() {}

    static JComponent build() {
        DisplayModeModel mode = new DisplayModeModel(k -> null, (k, v) -> {});
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(BorderFactory.createEmptyBorder(Tokens.M, Tokens.M, Tokens.M, Tokens.M));

        JPanel buttons = ContentStyle.controls();
        buttons.add(KitButton.primary("Start capture"));
        buttons.add(KitButton.secondary("Refresh"));
        buttons.add(KitButton.ghost("Clear"));
        buttons.add(KitButton.danger("Delete view"));
        buttons.add(KitButton.icon(new tomato.gui.modern.LineIcon(tomato.gui.modern.LineIcon.GEAR, 16), "Settings"));
        buttons.add(new SegmentedControl("gallery-mode", "Simple", "Analyst"));
        page.add(section("Buttons", buttons));

        JPanel chips = ContentStyle.controls();
        for (Tokens.Tone tone : Tokens.Tone.values()) chips.add(new Chip(tone.name().toLowerCase(), tone));
        chips.add(Chip.removable("Last 7 days", () -> {}));
        page.add(section("Chips", chips));

        FilterBar bar = new FilterBar("gallery", k -> null, (k, v) -> {});
        JTextField search = new JTextField(16);
        search.putClientProperty("JTextField.placeholderText", "Search runs");
        bar.search(search).drawer(new JLabel("Outcome · Evidence · Duration · Dates"));
        bar.setActive(Arrays.asList(new FilterBar.ActiveFilter("Completed", () -> {}), new FilterBar.ActiveFilter("Last 7 days", () -> {})), () -> {});
        bar.scope(new JComboBox<>(new String[]{"All sessions", "Current session"}));
        bar.overflow().add("Export page…", () -> {});
        page.add(section("Filter bar", bar));

        JPanel tiles = ContentStyle.responsiveGrid(4, 140, Tokens.S);
        StatTile runs = new StatTile("Runs today"); runs.setValue(DisplayValue.known("7", "Saved runs"), "5 completed");
        StatTile fame = new StatTile("Fame"); fame.setValue(DisplayValue.known("+1,480", "Fame samples"), "1,050 / hour");
        Sparkline trend = new Sparkline(); trend.setValues(new double[]{2472, 2520, 2600, 2750, 2878}); fame.trendSlot().add(trend);
        StatTile dps = new StatTile("Weapon DPS"); dps.setValue(DisplayValue.estimate("1,240", "Weapon formula, 0 defense"), null);
        StatTile pets = new StatTile("Pet"); pets.setValue(DisplayValue.unknown("Visit the Pet Yard with capture on to load pets"), null);
        tiles.add(runs); tiles.add(fame); tiles.add(dps); tiles.add(pets);
        page.add(section("Tiles", tiles));

        JPanel gear = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.XS, 0));
        gear.setOpaque(false);
        ItemSlot weapon = new ItemSlot(32); weapon.setItem(987_654_321, "UT");
        ItemSlot empty = new ItemSlot(32); empty.setEmpty();
        gear.add(weapon); gear.add(empty); gear.add(new ItemSlot(32));
        PipMeter exalts = new PipMeter(5); exalts.setFilled(3);
        StatBar def = new StatBar(); def.set(20, 25);
        StatBar att = new StatBar(); att.set(75, 75);
        gear.add(exalts); gear.add(def); gear.add(att);
        Card card = new Card(mode).title("Sharkbait · Wizard").body(gear).evidence("Equipment from the live character; backpack not captured.");
        card.header().setCount("7/8 maxed");
        card.onOpen("Open character", () -> {});
        page.add(card);

        JPanel gems = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.XS, 0));
        gems.setOpaque(false);
        EnchantInfo.Rarity[] rarities = {EnchantInfo.Rarity.UNCOMMON, EnchantInfo.Rarity.RARE, EnchantInfo.Rarity.LEGENDARY, EnchantInfo.Rarity.DIVINE};
        for (int size : new int[] {20, 24, 32, 48}) {
            for (EnchantInfo.Rarity rarity : rarities) {
                ItemSlot slot = new ItemSlot(size);
                slot.setItem(987_654_321, "UT", new EnchantInfo(EnchantInfo.State.RECORDED, rarity,
                    Collections.nCopies(rarity.ordinal(), new EnchantInfo.Slot(-1))));
                gems.add(slot);
            }
            ItemSlot unreadable = new ItemSlot(size);
            unreadable.setItem(987_654_321, "T12", EnchantInfo.unreadable());
            gems.add(unreadable);
        }
        page.add(section("Enchant gems", gems));

        page.add(new Collapsible("gallery-timeline", "Timeline", new JLabel("Area entered · Equipment changed · Exalt gained"), true, k -> null, (k, v) -> {}));
        page.add(new EmptyState("See your character here", "Start capture and enter the game to load your character.", KitButton.primary("Start capture")));

        CustomizableTabs tabs = new CustomizableTabs("gallery", mode, k -> null, (k, v) -> {})
            .add("overview", "Overview", new JLabel("Overview")).add("gear", "Gear", new JLabel("Gear")).addAnalyst("evidence", "Snapshot evidence", new JLabel("Evidence"));
        page.add(tabs.component());
        return ContentStyle.page(null, page, null);
    }

    private static JComponent section(String title, JComponent content) {
        JPanel section = new JPanel(new BorderLayout(0, Tokens.XS));
        section.setOpaque(false);
        section.add(new SectionHeader(title), BorderLayout.NORTH);
        section.add(content, BorderLayout.CENTER);
        section.setBorder(BorderFactory.createEmptyBorder(0, 0, Tokens.M, 0));
        return section;
    }
}
