package tomato.gui.glance.home;

import java.awt.*;
import javax.swing.*;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.KitText;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.StatBar;
import tomato.gui.kit.StatTile;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * The current or last known character (spec §6.1), compact so the whole Home fits a 1240×800 window (S1). The header row
 * holds the character's name as the card title, the Build action and Evidence. On a wide page one row holds the 54 px skin
 * sprite, class · level · fame and the maxed / exalt / last-seen chips, the four equipped slots with tier labels, and the
 * Weapon DPS and MP/sec estimate tiles (it wraps below 1000 px); then eight base-versus-cap bars (2 × 4) with the live boost;
 * then the needs line (left) and the account line (right) on one line. The whole card opens Characters; Build opens the
 * Build page. The pet rarity chip arrives with pet data in P3.
 */
final class HeroCard extends HomeCard {
    /** Canonical stat order: life, mana, atk, def, spd, dex, vit, wis. */
    static final String[] STATS = {"Life", "Mana", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
    /** Five exaltation tiers for each of the eight stats (spec §6.2). */
    static final int EXALT_TIERS = 40;
    private static final String[] SLOTS = {"Weapon", "Ability", "Armor", "Ring"};
    /** The card title while no character is shown. */
    private static final String NO_NAME = "Character";

    private final JLabel sprite = HomeViews.named(new JLabel(), "home-hero-sprite");
    private final JLabel name;
    private final KitText meta = HomeViews.named(HomeViews.caption(""), "home-hero-meta");
    private final Chip maxed = HomeViews.named(new Chip("", Tokens.Tone.WARN), "home-hero-maxed");
    private final Chip exalts = HomeViews.named(new Chip("", Tokens.Tone.ACCENT), "home-hero-exalts");
    private final Chip seen = HomeViews.named(new Chip("", Tokens.Tone.NEUTRAL), "home-hero-seen");
    private final ItemSlot[] gear = new ItemSlot[4];
    private final StatBar[] bars = new StatBar[8];
    private final KitText[] gearTiers = new KitText[4], values = new KitText[8], boosts = new KitText[8];
    private final KitText needs = HomeViews.named(HomeViews.caption(""), "home-hero-needs");
    private final KitText account = HomeViews.named(HomeViews.caption(""), "home-hero-account");
    private final StatTile weaponDps = HomeViews.named(new StatTile("Weapon DPS"), "home-hero-weapon-dps");
    private final StatTile mpPerSecond = HomeViews.named(new StatTile("MP/sec"), "home-hero-mp");
    private final EmptyState empty = HomeViews.named(new EmptyState("No character yet",
        "Start capture and enter the game to see your character.", null), "home-hero-empty");
    private JComponent footer;
    private final JComponent content;
    private HomeModel.Hero shown;
    private String shownSeen = "";

    HeroCard(Runnable openCharacters, Runnable openBuild, DisplayModeModel mode) {
        super(mode, "home-hero", "No capture evidence yet.");
        title(NO_NAME);
        name = titleLabel(header(), NO_NAME);
        name.setName("home-hero-name");
        KitButton build = HomeViews.named(KitButton.ghost("Build"), "home-build");
        build.setToolTipText("Weapon damage, recovery and estimates for this character");
        build.getAccessibleContext().setAccessibleName("Open Build: weapon damage and recovery");
        build.addActionListener(e -> openBuild.run());
        header().actions().add(build, 0);
        content = layoutContent();
        onOpen("Open Characters", openCharacters);
        status(HomeViews.LOADING, "Character: loading");
    }

    /** The kit header's title label, which shows the character's name (dimmed while not in game). */
    private static JLabel titleLabel(Container root, String text) {
        for (Component part : root.getComponents()) {
            if (part instanceof JLabel && text.equals(((JLabel) part).getText())) return (JLabel) part;
            if (part instanceof Container) { JLabel found = titleLabel((Container) part, text); if (found != null) return found; }
        }
        return null;
    }

    private JComponent layoutContent() {
        exalts.setToolTipText("Exaltation tiers completed for this class across the eight stats");
        JPanel identity = HomeViews.beside(HomeViews.stack(Tokens.XS, meta, HomeViews.wrap(maxed, exalts, seen)), sprite, BorderLayout.WEST, Tokens.M);
        JPanel slots = HomeViews.clear(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
        for (int i = 0; i < gear.length; i++) {
            gear[i] = HomeViews.named(new ItemSlot(32), "home-hero-slot-" + i);
            gearTiers[i] = HomeViews.named(HomeViews.caption(SLOTS[i]), "home-hero-tier-" + i);
            gearTiers[i].setHorizontalAlignment(SwingConstants.CENTER);
            slots.add(HomeViews.beside(gear[i], gearTiers[i], BorderLayout.SOUTH, 2));
        }
        JPanel stats = ContentStyle.responsiveGrid(4, 150, Tokens.S);
        stats.setOpaque(false);
        for (int i = 0; i < bars.length; i++) {
            bars[i] = HomeViews.named(new StatBar(), "home-hero-bar-" + i);
            bars[i].getAccessibleContext().setAccessibleName(STATS[i]);
            values[i] = HomeViews.named(HomeViews.caption(""), "home-hero-value-" + i);
            boosts[i] = HomeViews.named(new KitText("", Type.caption(), Tokens.Role.ACCENT_TEXT), "home-hero-boost-" + i);
            JPanel numbers = HomeViews.clear(new FlowLayout(FlowLayout.TRAILING, Tokens.XS, 0), values[i], boosts[i]);
            stats.add(HomeViews.beside(bars[i], HomeViews.beside(numbers, HomeViews.caption(STATS[i]), BorderLayout.WEST, Tokens.XS), BorderLayout.NORTH, 2));
        }
        // One row on a wide page: identity at the left, gear and the two estimates at the right (wrapping below 1000 px).
        JPanel top = HomeViews.named(HomeViews.spread(true, Tokens.M, identity, slots, weaponDps, mpPerSecond), "home-hero-top");
        footer = HomeViews.named(HomeViews.spread(false, Tokens.M, needs, account), "home-hero-footer");
        return HomeViews.named(HomeViews.stack(Tokens.S, top, stats, footer), "home-hero-content");
    }

    /** EDT only; updates prebuilt components in place and skips a hero that shows exactly what is shown (S9). */
    void apply(HomeModel.Hero hero, long now) {
        String seenText = seenText(hero, now);
        if (hero != null && hero.equals(shown) && seenText.equals(shownSeen)) return;
        shown = hero;
        shownSeen = seenText;
        if (hero == null || hero.state() == HomeModel.State.LOADING) { named(NO_NAME); status(HomeViews.LOADING, "Character: loading"); return; }
        explain(hero.evidence());
        if (hero.state() == HomeModel.State.UNAVAILABLE) {
            named(NO_NAME);
            unavailable(hero.evidence().isEmpty() ? "Character data is unavailable right now." : hero.evidence(), "Character: unavailable");
            return;
        }
        if (hero.state() == HomeModel.State.EMPTY) {
            named(NO_NAME);
            body(empty);
            getAccessibleContext().setAccessibleName("Character: none captured yet. Open Characters");
            getAccessibleContext().setAccessibleDescription(null);
            return;
        }
        boolean live = hero.state() == HomeModel.State.LIVE;
        String who = text(hero.name(), "Unnamed character"), kind = text(hero.className(), "Unknown class");
        sprite.setIcon(Sprites.sprite(hero.skin() != null && hero.skin() > 0 ? hero.skin() : hero.classId(), 54));
        sprite.setToolTipText(kind);
        named(who);
        DisplayValue fame = hero.fame() == null ? DisplayValue.unknown("Character fame not captured") : hero.fame();
        meta.setText(kind + (hero.level() == null ? "" : " · Level " + hero.level()) + " · Fame " + fame.display());
        meta.setToolTipText(fame.tooltip());
        chips(hero, seenText);
        gear(hero.equipment());
        bars(hero, live);
        needs.setText(text(hero.needsLine(), ""));
        needs.setVisible(!needs.getText().isEmpty());
        weaponDps.setValue(hero.weaponDps() == null ? DisplayValue.unknown("Not captured") : hero.weaponDps(), null);
        mpPerSecond.setValue(hero.mpPerSecond() == null ? DisplayValue.unknown("Not captured") : hero.mpPerSecond(), null);
        account.setText(text(hero.accountLine(), ""));
        account.setVisible(!account.getText().isEmpty());
        footer.setVisible(needs.isVisible() || account.isVisible());
        // The name stays stable while time passes; "Last seen N ago" is the description, so assistive technology is not re-announced.
        getAccessibleContext().setAccessibleName(who + ", " + kind + (hero.level() == null ? "" : " level " + hero.level())
            + (hero.maxed() >= 0 ? ", " + hero.maxed() + " of 8 maxed" : "") + (live ? "" : ", not in game") + ". Open Characters");
        getAccessibleContext().setAccessibleDescription(live ? null : seenText);
        body(content);
    }

    /** The card title: the character's name, dimmed while the character is not in game. */
    private void named(String title) {
        title(title);
        dim();
    }

    private void dim() {
        name.setForeground(Tokens.color(shown != null && shown.state() == HomeModel.State.STALE ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT));
    }

    @Override public void updateUI() {
        super.updateUI();
        if (name != null) SwingUtilities.invokeLater(this::dim); // after the kit header restores its own title color
    }

    /** "Last seen 2 h ago" for a character not in game; empty while live or without a character. */
    private static String seenText(HomeModel.Hero hero, long now) {
        if (hero == null || hero.state() != HomeModel.State.STALE) return "";
        return hero.lastSeenAt() > 0 ? "Last seen " + HomeViews.ago(hero.lastSeenAt(), now) : "Not in game";
    }

    private void chips(HomeModel.Hero hero, String seenText) {
        int count = hero.maxed(), tiers = hero.exaltTiers();
        maxed.setVisible(count >= 0); // -1 is unknown: never shown as 0/8
        if (count >= 0) {
            maxed.setText(count + "/8 maxed");
            maxed.setTone(count >= 8 ? Tokens.Tone.GOOD : Tokens.Tone.WARN);
        }
        exalts.setVisible(tiers >= 0);
        if (tiers >= 0) {
            exalts.setText("Exalts " + tiers + "/" + EXALT_TIERS);
            exalts.setTone(tiers >= EXALT_TIERS ? Tokens.Tone.GOOD : Tokens.Tone.ACCENT);
        }
        seen.setVisible(!seenText.isEmpty());
        seen.setText(seenText);
    }

    /** HomeModel.Hero.equipment: an item id > 0, 0 an empty slot, -1 (or a missing entry) a slot that was not captured. */
    private void gear(int[] equipment) {
        for (int i = 0; i < gear.length; i++) {
            int id = equipment != null && i < equipment.length ? equipment[i] : -1;
            String tier = id > 0 ? HomeViews.tier(id) : "";
            if (id > 0) gear[i].setItem(id, tier);
            else if (id == 0) gear[i].setEmpty();
            else gear[i].setUnknown();
            gearTiers[i].setText(tier.isEmpty() ? SLOTS[i] : tier);
        }
    }

    private void bars(HomeModel.Hero hero, boolean live) {
        for (int i = 0; i < bars.length; i++) {
            Integer base = at(hero.base(), i), cap = at(hero.caps(), i), total = at(hero.totals(), i);
            bars[i].set(base, cap);
            values[i].setText(base == null ? DisplayFormat.UNAVAILABLE : cap == null ? String.valueOf(base) : base + "/" + cap);
            // The boost from gear and effects only describes the character in game right now.
            int boost = live && base != null && total != null ? total - base : 0;
            boosts[i].setText(boost > 0 ? "+" + boost : "");
            boosts[i].setVisible(boost > 0);
            boosts[i].setToolTipText(boost > 0 ? STATS[i] + " is " + total + " right now with gear and effects" : null);
        }
    }

    private static Integer at(int[] values, int index) {
        return values == null || index >= values.length || values[index] < 0 ? null : values[index];
    }

    private static String text(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
}
