package tomato.gui.character;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.ProgressionData;
import tomato.gui.glance.character.CharacterCardModel;
import tomato.gui.glance.character.PetSummary;

/**
 * The Characters › Pets gallery (spec §6.2): pure, no Swing, built on the "character-pets" thread. Cards are the current
 * account's equipped pets from the journal (one per instance id, from every character that carries it, dead ones included) plus,
 * while the player is in the Pet Yard this connection, every yard pet from ProgressionData, merged by instance id. A pet's
 * identity is its instance id: a record without one is never merged ("No pet" and unknown pets are never cards). Pets that no
 * character equips are not remembered after leaving the yard (no journal change in P3b).
 */
final class PetGalleryModel {
    private PetGalleryModel() {}

    /**
     * One pet: {@code key} "pet:<instanceId>" or, for a yard pet without one, "object:<objectId>"; {@code equippedBy} "Wizard #7"
     * labels (class name and character id, " (dead)" for a character marked dead); {@code inYard} seen in the Pet Yard this visit;
     * {@code seenAt} the newest observation (epoch ms, 0 unknown).
     */
    record PetCard(String key, PetSummary pet, List<String> equippedBy, boolean inYard, long seenAt) {
        PetCard {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(pet, "pet");
            equippedBy = List.copyOf(equippedBy);
        }
    }

    /** A Pet Yard object as the journal would see it; {@code objectId} keys it when the pet has no instance id. */
    record YardPet(int objectId, CharacterJournal.PetRecord pet) {
        YardPet { Objects.requireNonNull(pet, "pet"); }
    }

    /**
     * The gallery for {@code account} (null: no equipped pets). {@code yardPets} are this account's Pet Yard pets ({@link #yard}).
     * Order: yard pets first, then equipped; each by max level (highest first, unknown last), then name, then instance id.
     */
    static List<PetCard> build(List<CharacterJournal.CharacterRecord> records, String account, List<YardPet> yardPets, PetDefinitions defs) {
        Map<Long, Equipped> equipped = new LinkedHashMap<>();
        if (account != null) for (CharacterJournal.CharacterRecord r : records) {
            CharacterJournal.PetRecord pet = r.pet;
            if (!account.equals(r.account) || pet == null || Boolean.TRUE.equals(pet.absent) || pet.instanceId == null) continue;
            Equipped known = equipped.computeIfAbsent(pet.instanceId, id -> new Equipped());
            // The newest observation supplies the values; an equal time keeps the first (the journal lists the latest seen first).
            if (known.pet == null || pet.observedAt > known.pet.observedAt) known.pet = pet;
            known.carriers.add(r);
        }
        List<Entry> yard = new ArrayList<>(), worn = new ArrayList<>();
        Map<Long, Entry> yardById = new LinkedHashMap<>();
        for (YardPet seen : yardPets) {
            Long id = seen.pet().instanceId;
            if (id == null) { yard.add(new Entry("object:" + seen.objectId(), seen.pet(), List.of())); continue; }
            Entry same = yardById.get(id);
            if (same != null) { same.pet = newer(same.pet, seen.pet()); continue; }
            Equipped carried = equipped.remove(id);
            Entry entry = carried == null ? new Entry("pet:" + id, seen.pet(), List.of())
                : new Entry("pet:" + id, newer(carried.pet, seen.pet()), carried.carriers);
            yardById.put(id, entry);
            yard.add(entry);
        }
        for (Map.Entry<Long, Equipped> e : equipped.entrySet())
            worn.add(new Entry("pet:" + e.getKey(), e.getValue().pet, e.getValue().carriers));
        List<PetCard> cards = new ArrayList<>(yard.size() + worn.size());
        for (Entry entry : sorted(yard)) cards.add(entry.card(true, defs));
        for (Entry entry : sorted(worn)) cards.add(entry.card(false, defs));
        return List.copyOf(cards);
    }

    /**
     * This account's Pet Yard pets: the ProgressionData pets that are Pet Yard objects (a non-negative object id; the character
     * list's equipped-pet metadata uses -1 and is already in the journal) while its scope is {@code account}. ProgressionData is
     * cleared on every map change, so these are the pets seen in the yard this visit. The panel passes a null account only for its
     * own unbound preview source.
     */
    static List<YardPet> yard(ProgressionData.Snapshot snapshot, String account) {
        if (!Objects.equals(snapshot.scope.account, account)) return List.of();
        List<YardPet> pets = new ArrayList<>();
        for (ProgressionData.Pet pet : snapshot.pets) if (pet.objectId >= 0) pets.add(new YardPet(pet.objectId, record(pet)));
        return List.copyOf(pets);
    }

    private static final StatType[][] ABILITIES = {
        {StatType.PET_FIRST_ABILITY_POINT_STAT, StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_FIRST_ABILITY_TYPE_STAT},
        {StatType.PET_SECOND_ABILITY_POINT_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_TYPE_STAT},
        {StatType.PET_THIRD_ABILITY_POINT_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_TYPE_STAT}};

    /**
     * A yard pet as a journal PetRecord, converted exactly as TomatoData.yardPetRecord converts a Pet Yard entity (which this
     * package cannot call): every field null when not captured, a missing or negative ability value -1. Unlike that method, a pet
     * without an instance id is kept (it gets its own card), and the source is the observation's own ("Pet Yard capture", or
     * "Unverified preview" for the panel's unbound preview).
     */
    static CharacterJournal.PetRecord record(ProgressionData.Pet value) {
        CharacterJournal.PetRecord pet = new CharacterJournal.PetRecord();
        Integer id = value.value(StatType.PET_INSTANCE_ID_STAT);
        pet.instanceId = id == null ? null : (long) id;
        pet.name = value.text(StatType.PET_NAME_STAT);
        pet.type = value.value(StatType.PET_TYPE_STAT); pet.rarity = value.value(StatType.PET_RARITY_STAT);
        pet.family = value.value(StatType.PET_FAMILY_STAT); pet.maxAbilityPower = value.value(StatType.PET_MAX_ABILITY_POWER_STAT);
        pet.skin = value.value(StatType.SKIN_ID);
        for (int i = 0; i < 3; i++) {
            Integer points = value.value(ABILITIES[i][0]), level = value.value(ABILITIES[i][1]), type = value.value(ABILITIES[i][2]);
            pet.abilityPoints[i] = points == null || points < 0 ? -1 : points;
            pet.abilityLevel[i] = level == null || level < 0 ? -1 : level;
            pet.abilityType[i] = type == null || type < 0 ? -1 : type;
        }
        pet.observedAt = Math.max(0, value.capturedAt); pet.source = value.source;
        return pet;
    }

    /**
     * Home's current account (Characters › Exalts uses the same rule): the character in game now, else the last one seen this run,
     * else the journal's most recently played character; null when none is known (the gallery is then empty).
     */
    static String account(LiveCharacter.Snapshot current, LiveCharacter.Snapshot lastKnown, CharacterJournal.CharacterRecord mostRecent) {
        if (current != null) return current.account();
        if (lastKnown != null) return lastKnown.account();
        return mostRecent == null ? null : mostRecent.account;
    }

    /** "Wizard #7", "Priest #9 (dead)": the saved class name (else the loaded class definitions', else "Class <id>") and character id. */
    static String label(CharacterJournal.CharacterRecord r) {
        return CharacterCardModel.className(r.classId, r.className) + " #" + r.characterId + (r.dead ? " (dead)" : "");
    }

    /**
     * The two observations of one pet as one: the newer one's known values over the older one's (as CharacterJournal.yardPet
     * merges a yard pet into a character's), the newer time and source. Equal times prefer {@code b}, the yard.
     */
    private static CharacterJournal.PetRecord newer(CharacterJournal.PetRecord a, CharacterJournal.PetRecord b) {
        CharacterJournal.PetRecord older = b.observedAt >= a.observedAt ? a : b, newer = older == a ? b : a;
        CharacterJournal.PetRecord pet = new CharacterJournal.PetRecord();
        pet.instanceId = newer.instanceId != null ? newer.instanceId : older.instanceId;
        pet.name = newer.name != null ? newer.name : older.name;
        pet.type = newer.type != null ? newer.type : older.type;
        pet.rarity = newer.rarity != null ? newer.rarity : older.rarity;
        pet.family = newer.family != null ? newer.family : older.family;
        pet.skin = newer.skin != null ? newer.skin : older.skin;
        pet.maxAbilityPower = newer.maxAbilityPower != null ? newer.maxAbilityPower : older.maxAbilityPower;
        pet.abilityType = slots(newer.abilityType, older.abilityType);
        pet.abilityLevel = slots(newer.abilityLevel, older.abilityLevel);
        pet.abilityPoints = slots(newer.abilityPoints, older.abilityPoints);
        pet.observedAt = newer.observedAt;
        pet.source = newer.source != null ? newer.source : older.source;
        return pet;
    }
    /** Slot by slot, the newer known value (not -1), else the older one; a missing or malformed array counts as unknown. */
    private static int[] slots(int[] newer, int[] older) {
        int[] result = {-1, -1, -1};
        for (int i = 0; i < 3; i++) {
            int value = newer != null && newer.length == 3 ? newer[i] : -1;
            result[i] = value >= 0 ? value : older != null && older.length == 3 && older[i] >= 0 ? older[i] : -1;
        }
        return result;
    }

    private static final Comparator<Entry> ORDER = Comparator
        .comparing((Entry e) -> e.pet.maxAbilityPower, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(e -> e.pet.name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
        .thenComparing(e -> e.pet.instanceId, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(e -> e.key);

    private static List<Entry> sorted(List<Entry> entries) { List<Entry> copy = new ArrayList<>(entries); copy.sort(ORDER); return copy; }

    /** The characters carrying one equipped pet, and its newest record. */
    private static final class Equipped {
        CharacterJournal.PetRecord pet;
        final List<CharacterJournal.CharacterRecord> carriers = new ArrayList<>();
    }

    private static final class Entry {
        final String key;
        final List<CharacterJournal.CharacterRecord> carriers;
        CharacterJournal.PetRecord pet;
        Entry(String key, CharacterJournal.PetRecord pet, List<CharacterJournal.CharacterRecord> carriers) {
            this.key = key; this.pet = pet; this.carriers = carriers;
        }
        /** The living carriers first, then the dead; each by character id. */
        PetCard card(boolean inYard, PetDefinitions defs) {
            List<CharacterJournal.CharacterRecord> ordered = new ArrayList<>(carriers);
            ordered.sort(Comparator.comparing((CharacterJournal.CharacterRecord r) -> r.dead).thenComparingInt(r -> r.characterId));
            List<String> labels = new ArrayList<>();
            for (CharacterJournal.CharacterRecord r : ordered) labels.add(label(r));
            return new PetCard(key, PetSummary.of(pet, defs), labels, inYard, Math.max(0, pet.observedAt));
        }
    }
}
