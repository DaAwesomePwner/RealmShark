package tomato.gui.glance.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.PetFeeding;

/**
 * The one pet view model (sheet Pet tab, Overview pet card, Pets gallery, Home hero chip); pure, no Swing, built off the EDT.
 * {@code state} keeps the journal's distinction: UNKNOWN (no PetRecord, pet not captured) is never "No pet" (NONE, the character
 * list reported an empty pet). Every other component is null when unknown; a captured zero stays zero. {@code rarity} and
 * {@code family} are names (null unknown), {@code rarityValue} the captured number; {@code family} comes from pets.xml by type.
 * {@code abilities} always holds the three slots in order. Lists are immutable and compare by content.
 */
public record PetSummary(State state, Long instanceId, String name, Integer skin, Integer type, Integer rarityValue,
                         String rarity, String family, Integer maxLevel, List<Ability> abilities, long observedAt, String source) {
    public enum State { UNKNOWN, NONE, KNOWN }

    /** Slot 0–2; null values unknown; locked when the pet's max level is known and below the slot's unlock (slot 1: 50, slot 2: 90). */
    public record Ability(int slot, Integer type, String name, Integer level, Integer points, boolean locked) {}

    public static final PetSummary UNKNOWN = new PetSummary(State.UNKNOWN, null, null, null, null, null, null, null, null, unknownSlots(), 0, null);

    public PetSummary {
        Objects.requireNonNull(state, "state");
        abilities = List.copyOf(abilities);
        if (abilities.size() != 3) throw new IllegalArgumentException("A pet has three ability slots");
    }

    /** null → UNKNOWN; absent TRUE → NONE (observedAt/source kept); otherwise KNOWN with names from defs (null or loading: family unknown). */
    public static PetSummary of(CharacterJournal.PetRecord pet, PetDefinitions defs) {
        if (pet == null) return UNKNOWN;
        if (Boolean.TRUE.equals(pet.absent))
            return new PetSummary(State.NONE, null, null, null, null, null, null, null, null, unknownSlots(), pet.observedAt, pet.source);
        List<Ability> abilities = new ArrayList<>(3);
        for (int slot = 0; slot < 3; slot++) {
            Integer type = slot(pet.abilityType, slot);
            String name = type == null ? null : Objects.requireNonNullElse(PetDefinitions.ability(type), "Ability #" + type);
            abilities.add(new Ability(slot, type, name, slot(pet.abilityLevel, slot), slot(pet.abilityPoints, slot), locked(slot, pet.maxAbilityPower)));
        }
        return new PetSummary(State.KNOWN, pet.instanceId, pet.name, pet.skin, pet.type, pet.rarity, PetDefinitions.rarity(pet.rarity),
            defs == null ? null : defs.family(pet.type), pet.maxAbilityPower, abilities, pet.observedAt, pet.source);
    }

    /** "Legendary pet", "No pet", or null (unknown pet, or a known pet whose rarity is unknown). */
    public String chip() {
        if (state == State.NONE) return "No pet";
        return state == State.KNOWN && rarity != null ? rarity + " pet" : null;
    }

    /** The pet's name, else "Pet". */
    public String title() { return name == null || name.isBlank() ? "Pet" : name; }

    /** The journal keeps -1 for unknown; a missing or malformed array (not from a loaded journal) is unknown too. */
    private static Integer slot(int[] values, int slot) {
        return values == null || values.length != 3 || values[slot] < 0 ? null : values[slot];
    }
    /**
     * PetFeeding's unlock rule (slot 1 below max level 50, slot 2 below 90), taken from its estimate so the Pet tab and the feeding
     * estimate never disagree. An unknown max level locks nothing; so does one outside 1–100, which the estimate calls unsupported.
     */
    private static boolean locked(int slot, Integer maxLevel) {
        return slot > 0 && maxLevel != null && PetFeeding.estimate(slot, null, null, maxLevel, 1).locked;
    }
    private static List<Ability> unknownSlots() {
        return List.of(new Ability(0, null, null, null, null, false), new Ability(1, null, null, null, null, false), new Ability(2, null, null, null, null, false));
    }
}
