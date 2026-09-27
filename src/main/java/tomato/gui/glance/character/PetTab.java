package tomato.gui.glance.character;

import java.awt.*;
import java.util.Objects;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.PetFeeding;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Pet (spec §6.2): this character's own pet, from its journal record. A known pet shows its sprite, name, rarity, family
 * and max ability level, then each of the three abilities (its name and its level toward the max level, or "Locked"), and when
 * and from where it was observed. A collapsed "Feeding estimate" drawer runs PetFeeding's local formula for a feed power the user
 * enters; the numbers are estimates ("≈") and change nothing in game. "No pet" (the character list reported none) and a pet not
 * captured yet each have their own empty state; null (the sheet is loading) shows nothing. EDT only.
 */
final class PetTab extends JPanel {
    /** Why a pet is unknown: TomatoData requests the character list only on entering the Pet Yard or the Daily Quest Room. */
    static final String UNKNOWN_REASON = "Pet details arrive when capture reads your character list in the Pet Yard or the Daily Quest Room";
    static final String FEED_INVALID = "Enter a positive whole number (1–2,147,483,647).";
    static final int DEFAULT_FEED_POWER = 500;
    /** PetFeeding's unlock rule, by slot: slot 1 needs max ability level 50, slot 2 needs 90 (slot 0 is never locked). */
    private static final int[] UNLOCK = {0, 50, 90};
    private final JLabel sprite = named(new JLabel(), "character-pet-sprite");
    private final KitText name = named(KitText.emphasis(""), "character-pet-name");
    private final Chip rarity = named(new Chip("", Tokens.Tone.NEUTRAL), "character-pet-rarity");
    private final KitText family = named(KitText.body(""), "character-pet-family");
    private final KitText max = named(KitText.body(""), "character-pet-max");
    private final KitText[] names = new KitText[3], levels = new KitText[3], slots = new KitText[3];
    /** Wrapping text: an estimate line is long and must not be cut at 680 px or font 18. */
    private final JTextArea[] estimates = new JTextArea[3];
    private final StatBar[] bars = new StatBar[3];
    private final KitText observed = named(KitText.caption(""), "character-pet-observed");
    private final JTextField feedPower = named(new JTextField(Integer.toString(DEFAULT_FEED_POWER), 8), "character-pet-feed-power");
    private final KitText feedError = named(new KitText(FEED_INVALID, Type.caption(), Tokens.Role.BAD), "character-pet-feed-error");
    private final JPanel content;
    private final EmptyState none = named(new EmptyState("No pet",
        "This character had no pet equipped when capture last read the character list.", null), "character-pet-none");
    private final EmptyState empty = named(new EmptyState("Pet not captured yet", UNKNOWN_REASON + ".", null), "character-pet-empty");
    private PetSummary shown;
    private String shownObserved = "", shownFamilyReason = "";
    private boolean applied;

    PetTab() {
        super(new BorderLayout());
        setOpaque(false);
        setName("character-pet");
        sprite.getAccessibleContext().setAccessibleName("Pet sprite");
        JPanel identity = beside(KitLayouts.stack(Tokens.XS, row(name, rarity), family, max), sprite, BorderLayout.WEST, Tokens.M);
        JPanel abilities = new JPanel(new GridBagLayout());
        abilities.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(Tokens.XS, 0, Tokens.XS, Tokens.M);
        for (int i = 0; i < 3; i++) {
            names[i] = named(KitText.body(""), "character-pet-ability-name-" + i);
            bars[i] = named(new StatBar(), "character-pet-ability-bar-" + i);
            bars[i].getAccessibleContext().setAccessibleName("Ability " + (i + 1) + " level");
            levels[i] = named(KitText.caption(""), "character-pet-ability-level-" + i);
            c.gridy = i;
            c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            abilities.add(names[i], c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            abilities.add(bars[i], c);
            c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            abilities.add(levels[i], c);
        }
        Collapsible feeding = named(new Collapsible("character-pet-feeding", "Feeding estimate", feeding(), false), "character-pet-feeding");
        content = named(KitLayouts.stack(Tokens.M, identity, abilities, observed, feeding), "character-pet-content");
        add(KitLayouts.stack(Tokens.M, content, none, empty), BorderLayout.NORTH);
        feedPower.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { estimate(); }
            @Override public void removeUpdate(DocumentEvent e) { estimate(); }
            @Override public void changedUpdate(DocumentEvent e) { estimate(); }
        });
        for (JComponent control : new JComponent[]{feeding.toggle(), feedPower}) control.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); }
        });
        apply(null);
    }

    /** The drawer's body: the feed power field, its rose helper line, one estimate per slot and the caption. */
    private JPanel feeding() {
        KitText label = KitText.body("Feed power per item");
        label.setLabelFor(feedPower);
        feedPower.getAccessibleContext().setAccessibleName("Feed power per item");
        feedError.setVisible(false);
        JComponent[] rows = new JComponent[3];
        for (int i = 0; i < 3; i++) {
            slots[i] = named(KitText.caption(""), "character-pet-estimate-label-" + i);
            estimates[i] = named(ContentStyle.wrappingText(""), "character-pet-estimate-" + i);
            rows[i] = KitLayouts.stack(2, slots[i], estimates[i]);
        }
        JTextArea caption = named(ContentStyle.wrappingText("Estimates use RealmShark's local feeding formula and cost table; they change nothing in game."),
            "character-pet-feeding-caption");
        return KitLayouts.stack(Tokens.S, row(label, feedPower), feedError, KitLayouts.stack(Tokens.S, rows), caption);
    }

    /**
     * EDT. Called for every model and once a second (SheetPresenter.times): an equal pet is skipped while its "Observed …" age and
     * the reason for an unknown family (the pet names' status) read the same.
     */
    void apply(PetSummary pet) {
        String seen = observed(pet), familyReason = familyReason(pet);
        if (applied && Objects.equals(pet, shown) && seen.equals(shownObserved) && familyReason.equals(shownFamilyReason)) return;
        applied = true;
        shown = pet;
        shownObserved = seen;
        shownFamilyReason = familyReason;
        boolean known = pet != null && pet.state() == PetSummary.State.KNOWN;
        content.setVisible(known);
        none.setVisible(pet != null && pet.state() == PetSummary.State.NONE);
        empty.setVisible(pet != null && pet.state() == PetSummary.State.UNKNOWN); // null: loading, neither
        if (known) fill(pet, seen, familyReason);
        estimate();
        revalidate();
        repaint();
    }

    private void fill(PetSummary pet, String seen, String familyReason) {
        sprite.setIcon(Sprites.sprite(pet.skin() == null ? 0 : pet.skin(), 40)); // an id <= 0 is the placeholder
        sprite.setToolTipText(pet.title());
        name.setText(pet.title());
        rarity.setVisible(pet.rarity() != null); // unknown rarity: no chip, never a guess
        rarity.setText(pet.rarity() == null ? "" : pet.rarity());
        family.setText("Family: " + (pet.family() == null ? DisplayFormat.UNAVAILABLE : pet.family()));
        family.setToolTipText(pet.family() == null ? familyReason : "From the selected game assets' pet names");
        max.setText("Max level " + (pet.maxLevel() == null ? DisplayFormat.UNAVAILABLE : pet.maxLevel()));
        max.setToolTipText(pet.maxLevel() == null ? "Maximum ability level not captured" : "Maximum ability level");
        for (int i = 0; i < 3; i++) {
            PetSummary.Ability a = pet.abilities().get(i);
            names[i].setText(a.name() == null ? DisplayFormat.UNAVAILABLE : a.name());
            names[i].setToolTipText(a.name() == null ? "Ability not captured" : null);
            if (a.locked()) {
                String why = "Locked until the pet's max ability level reaches " + UNLOCK[i];
                bars[i].set(null, null);
                bars[i].setToolTipText(why);
                bars[i].getAccessibleContext().setAccessibleDescription(why);
                levels[i].setText("Locked");
                levels[i].setToolTipText(why);
            } else {
                bars[i].set(a.level(), pet.maxLevel());
                levels[i].setText("Level " + (a.level() == null ? DisplayFormat.UNAVAILABLE
                    : a.level() + " / " + (pet.maxLevel() == null ? DisplayFormat.UNAVAILABLE : pet.maxLevel())));
                levels[i].setToolTipText(a.level() == null ? "Ability level not captured" : null);
            }
        }
        observed.setText(seen);
        observed.setToolTipText(pet.observedAt() > 0 ? DisplayFormat.formatTimestamp(pet.observedAt()) : null);
    }

    /** Recomputes the estimates for the shown pet and the feed power field; an invalid feed power shows the helper line and none. */
    private void estimate() {
        Integer power = feedPower();
        feedError.setVisible(power == null);
        feedPower.getAccessibleContext().setAccessibleDescription(power == null ? FEED_INVALID : "Feed power of each item used in the estimate");
        PetSummary pet = shown;
        boolean known = pet != null && pet.state() == PetSummary.State.KNOWN;
        for (int i = 0; i < 3; i++) {
            boolean show = power != null && known;
            slots[i].setVisible(show);
            estimates[i].setVisible(show);
            if (!show) { estimates[i].setText(""); estimates[i].setToolTipText(null); continue; }
            PetSummary.Ability a = pet.abilities().get(i);
            slots[i].setText(a.name() == null ? "Ability " + (i + 1) : a.name());
            PetFeeding.Estimate e = a.locked() ? null : PetFeeding.estimate(i, a.level(), a.points(), pet.maxLevel(), power);
            estimates[i].setText(text(e));
            estimates[i].setToolTipText(e == null ? "Locked until the pet's max ability level reaches " + UNLOCK[i]
                : e.reason + " · " + DisplayFormat.formatInteger(power) + " feed power per item");
        }
        revalidate();
        repaint();
    }

    /** One slot's estimate, straight from PetFeeding; null or a locked estimate is locked; missing inputs say what is missing. */
    static String text(PetFeeding.Estimate e) {
        if (e == null || e.locked) return "Locked: no estimate";
        if (e.nextItems == null || e.maxItems == null) return "No estimate: " + e.reason;
        if (e.fullyFed) return "At max level: nothing to feed";
        return "Next level ≈ " + items(e.nextItems) + " · " + fame(e.nextFame) + " · To max ≈ " + items(e.maxItems) + " · " + fame(e.maxFame);
    }
    private static String items(long n) { return DisplayFormat.formatInteger(n) + (n == 1 ? " item" : " items"); }
    /** Fame is unknown (never 0) when the max level has no cost tier; the estimate's reason (the tooltip) says so. */
    private static String fame(Long fame) { return fame == null ? "fame " + DisplayFormat.UNAVAILABLE : "≈ " + DisplayFormat.formatInteger(fame) + " fame"; }

    /** The feed power field's value when it is a positive whole number, else null. */
    private Integer feedPower() {
        try {
            int value = Integer.parseInt(feedPower.getText().trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    /** "Observed 2 h ago · Character list" for a known pet (the age advances on every apply); "" otherwise. */
    private static String observed(PetSummary pet) {
        if (pet == null || pet.state() != PetSummary.State.KNOWN) return "";
        return "Observed " + (pet.observedAt() > 0 ? KitFormat.relative(pet.observedAt()) : "at an unknown time")
            + (pet.source() == null || pet.source().isBlank() ? "" : " · " + pet.source());
    }

    /**
     * Why a known pet's family is unknown: its type was not captured, or the pet names' status (loading, unavailable, or a type
     * the selected assets do not name). PetDefinitions.current() never blocks. "" when the family is known or there is no pet.
     */
    private static String familyReason(PetSummary pet) {
        if (pet == null || pet.state() != PetSummary.State.KNOWN || pet.family() != null) return "";
        return pet.type() == null ? "The pet's type was not captured, so its family is unknown" : PetDefinitions.current().status;
    }
}
