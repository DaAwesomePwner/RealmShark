package tomato.gui.glance.character;

import java.util.*;
import java.util.function.IntFunction;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.home.HomeModelBuilder;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.ItemTiers;
import tomato.gui.modern.DisplayFormat;
import tomato.planning.PlanningMetadata;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.CharacterClass;

/**
 * Pure, static builder of the character sheet model (spec §6.2). No Swing (only the kit's DisplayValue and ItemTiers, which use
 * none): SheetPresenter calls it on the "character-sheet" thread with detached journal copies. Potions and maxed counts use
 * Home's arithmetic, so the hero and the sheet agree.
 */
public final class SheetModelBuilder {
    /** Exaltation tier thresholds, as CharacterJournal.exaltLevel counts them. */
    static final int[] THRESHOLDS = {5, 15, 30, 50, 75};
    static final int SUMMARY_STATS = 3;
    static final String FAME_UNKNOWN = "Enter the game with capture on to read character fame";

    private SheetModelBuilder() {}

    /**
     * The sheet of {@code record}, or null when the journal has no such character. {@code live} is the character in game now,
     * whoever it is (null = nobody); its values apply only when it is this character. {@code pets} names the pet (its family);
     * the presenter passes PetDefinitions.current(), which may still be loading (the family is then unknown). Dungeons stay
     * unknown while the mapping loads.
     */
    public static SheetModel build(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live, PetDefinitions pets,
                                   RosterDefinitions defs, long now) {
        PlanningMetadata planning = PlanningMetadata.current();
        return build(record, account, live, pets, defs, planning.available ? planning::dungeons : null, now);
    }

    /**
     * The character in game now, or null: the current snapshot, or while a map change's brief clear lasts (Home's grace,
     * {@link HomeModelBuilder#stillCurrent}) the last known one, so "Playing now" does not flicker. Any thread.
     */
    public static LiveCharacter.Snapshot inGame(LiveCharacter live, long now) {
        if (live == null) return null;
        LiveCharacter.Snapshot current = live.current();
        if (current != null) return current;
        return HomeModelBuilder.stillCurrent(live.lastSeenAt(), live.lastBoundary(), now) ? live.lastKnown() : null;
    }

    /**
     * Tests written before pet names: {@link #build(CharacterRecord, AccountRecord, LiveCharacter.Snapshot, PetDefinitions,
     * RosterDefinitions, IntFunction, long)} with no pet names (the pet's family unknown, as while PetDefinitions loads).
     */
    static SheetModel build(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live, RosterDefinitions defs,
                            IntFunction<List<String>> dungeons, long now) {
        return build(record, account, live, null, defs, dungeons, now);
    }

    /**
     * {@code pets} null reads as not loaded (the pet's family unknown); {@code dungeons}: canonical stat index to dungeon names, or
     * null while the mapping is loading or unavailable.
     */
    static SheetModel build(CharacterRecord record, AccountRecord account, LiveCharacter.Snapshot live, PetDefinitions pets,
                            RosterDefinitions defs, IntFunction<List<String>> dungeons, long now) {
        if (record == null) return null;
        RosterDefinitions definitions = defs == null ? RosterDefinitions.empty() : defs;
        boolean playing = live != null && Objects.equals(live.account(), record.account) && live.characterId() == record.characterId;
        int[] liveBase = playing ? live.base() : null, totals = playing ? live.totals() : null;
        int[] base = new int[8], caps = new int[8], boosts = new int[8];
        for (int i = 0; i < 8; i++) {
            Integer saved = record.stats != null && i < record.stats.length ? record.stats[i] : null, cap = definitions.cap(record.classId, i);
            base[i] = liveBase != null && liveBase[i] >= 0 ? liveBase[i] : saved == null || saved < 0 ? -1 : saved;
            caps[i] = cap == null ? -1 : cap;
            boosts[i] = totals != null && totals[i] >= 0 && base[i] >= 0 ? Math.max(0, totals[i] - base[i]) : 0;
        }
        int[] needed = HomeModelBuilder.potionsNeeded(base, caps);
        int maxed = HomeModelBuilder.maxed(needed);
        return new SheetModel(record.key, identity(record, live, playing, maxed, now), stats(record, account, base, caps, boosts, needed, maxed, liveBase),
            gear(record, live, playing, definitions), exalts(record.classId, account, dungeons), PetSummary.of(record.pet, pets), death(record),
            liveRef(live));
    }

    private static SheetModel.Identity identity(CharacterRecord r, LiveCharacter.Snapshot live, boolean playing, int maxed, long now) {
        String kind = r.className != null && !r.className.isBlank() ? r.className : className(r.classId);
        String name = r.name != null && !r.name.isBlank() ? r.name : kind + " #" + r.characterId;
        Integer level = playing && live.level() != null ? live.level() : r.level, skin = playing && live.skin() != null ? live.skin() : r.skin;
        DisplayValue fame = playing && live.characterFame() != null ? DisplayValue.count(live.characterFame(), "Live character stats", FAME_UNKNOWN)
            : r.fame == null ? DisplayValue.unknown(FAME_UNKNOWN)
            : DisplayValue.stale(DisplayFormat.formatInteger(r.fame.longValue()), "Saved in the character journal, last seen " + when(r.lastSeen));
        return new SheetModel.Identity(name, r.classId, kind, skin, level, fame, r.seasonal, r.dead, playing ? now : r.lastSeen,
            playing ? now : r.lastObservedAlive, playing, maxed);
    }

    private static SheetModel.Stats stats(CharacterRecord r, AccountRecord account, int[] base, int[] caps, int[] boosts, int[] needed,
                                          int maxed, int[] liveBase) {
        // Journal v5 records the regular vault only; a seasonal character cannot use it, so its vault count stays unknown.
        int[] vault = Boolean.TRUE.equals(r.seasonal) || account == null || account.vaultPotions == null || account.vaultPotions.length != 8
            ? null : account.vaultPotions;
        List<String> needs = new ArrayList<>(), evidence = new ArrayList<>();
        int unknown = 0;
        for (int i = 0; i < 8; i++) {
            if (needed[i] < 0) unknown++;
            else if (needed[i] > 0) needs.add(HomeModelBuilder.statLabel(i) + " needs " + needed[i] + (vault != null && vault[i] >= 0 ? " · " + vault[i] + " in vault" : ""));
            evidence.add(liveBase != null && liveBase[i] >= 0 ? "Live character stats" : evidence(r, "stat." + i, base[i] >= 0));
        }
        return new SheetModel.Stats(list(base), list(caps), list(boosts), list(needed), vault == null ? null : list(vault),
            vault == null ? 0 : account.vaultPotionsObservedAt, List.copyOf(needs), unknown, maxed, List.copyOf(evidence));
    }

    /**
     * Saved slots, with the live equipped four and their enchant rarity while this character plays. Tier labels come from the
     * definitions this build was given (ItemTiers' pure overload), never from the global RosterDefinitions.current() on the EDT.
     */
    private static SheetModel.Gear gear(CharacterRecord r, LiveCharacter.Snapshot live, boolean playing, RosterDefinitions definitions) {
        int[] equipped = playing ? live.equipment() : null, slots = new int[28];
        List<String> tiers = new ArrayList<>(28);
        for (int i = 0; i < 28; i++) {
            Integer item = i < 4 && equipped != null ? Integer.valueOf(equipped[i]) : r.equipment != null && i < r.equipment.length ? r.equipment[i] : null;
            slots[i] = item == null ? -1 : item > 0 ? item : 0;
            tiers.add(slots[i] > 0 ? ItemTiers.label(definitions.item(slots[i])) : "");
        }
        return new SheetModel.Gear(list(slots), List.copyOf(tiers), r.hasBackpack, playing ? enchants(live) : null);
    }

    /** Unlocked enchant slots of the 4 equipped items from the live snapshot's detached inputs; -1 where not decodable. */
    static List<Integer> enchants(LiveCharacter.Snapshot live) {
        if (live.build() == null) return null;
        ParseEnchants.EquippedCapture capture = live.build().enchants();
        int[] slots = new int[4];
        for (int i = 0; i < 4; i++) slots[i] = capture.unlockedSlots(i);
        return list(slots);
    }

    /** Exalt arrays are in RealmCharacter order (dex, spd, vit, wis, def, atk, mana, life); EXALT_ORDER maps canonical stats into them. */
    static SheetModel.Exalts exalts(int classId, AccountRecord account, IntFunction<List<String>> dungeons) {
        int[] counts = account == null || account.exalts == null ? null : account.exalts.get(classId);
        boolean known = counts != null && counts.length == 8 && Arrays.stream(counts).allMatch(count -> count >= 0);
        int[] completions = new int[8], tiers = new int[8], toNext = new int[8];
        int total = known ? 0 : -1, lowest = known ? 5 : -1;
        for (int i = 0; i < 8; i++) {
            int count = known ? counts[CharacterJournal.EXALT_ORDER[i]] : -1;
            completions[i] = count; tiers[i] = known ? CharacterJournal.exaltLevel(count) : -1; toNext[i] = known ? toNext(count) : -1;
            if (known) { total += count; lowest = Math.min(lowest, tiers[i]); }
        }
        CharacterJournal.ExaltBonus bonus = account == null || account.liveExaltBonus == null ? null : account.liveExaltBonus.get(classId);
        boolean bonusKnown = bonus != null && bonus.bonus != null && bonus.bonus.length == 8;
        Long seen = account == null || account.exaltSeenByClass == null ? null : account.exaltSeenByClass.get(classId);
        List<String> earnIn = null;
        if (dungeons != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 8; i++) { List<String> where = dungeons.apply(i); names.add(where == null ? "" : String.join(" · ", where)); }
            earnIn = List.copyOf(names);
        }
        List<Integer> tierList = list(tiers);
        return new SheetModel.Exalts(list(completions), tierList, list(toNext), total, lowest, bonusKnown ? list(bonus.bonus) : null,
            bonusKnown ? bonus.observedAt : 0, seen == null ? 0 : seen, earnIn, known ? summary(tierList) : "");
    }

    /** Completions still needed for the next tier; 0 once the last tier (75 completions) is reached. */
    static int toNext(int count) { for (int goal : THRESHOLDS) if (count < goal) return goal - count; return 0; }

    /** "LIFE 5/5 · DEF 4/5 · ATT 3/5 · +2 more": highest tiers first (ties keep stat order); N counts other stats with a tier. */
    static String summary(List<Integer> tiers) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 8; i++) if (tiers.get(i) > 0) order.add(i);
        if (order.isEmpty()) return "No exalt tiers yet";
        order.sort(Comparator.comparingInt(i -> -tiers.get(i)));
        List<String> parts = new ArrayList<>();
        for (int i : order.subList(0, Math.min(SUMMARY_STATS, order.size()))) parts.add(HomeModelBuilder.statLabel(i) + " " + tiers.get(i) + "/5");
        int more = order.size() - SUMMARY_STATS;
        return String.join(" · ", parts) + (more > 0 ? " · +" + more + " more" : "");
    }

    private static SheetModel.Death death(CharacterRecord r) {
        if (!r.dead) return null;
        CharacterJournal.DeathAnnotation a = r.deathAnnotation;
        return new SheetModel.Death(a != null && a.markedAt > 0 ? a.markedAt : r.diedAt, a == null ? null : a.occurredAt, a == null || a.notes == null ? "" : a.notes);
    }

    private static SheetModel.Live liveRef(LiveCharacter.Snapshot live) {
        if (live == null || live.account() == null || live.characterId() < 0) return null;
        return new SheetModel.Live(live.account() + ":" + live.characterId(), className(live.classId()), live.characterId());
    }

    private static String evidence(CharacterRecord r, String field, boolean known) {
        if (!known) return "Not captured";
        FieldCapture capture = r.fields == null ? null : r.fields.get(field);
        if (capture == null) return "Legacy / provenance unknown";
        return SheetViews.fieldEvidence(capture.source, capture.at, r.lastSeen);
    }

    private static String className(int classId) { String name = CharacterClass.getName(classId); return name == null || name.isEmpty() ? "Class #" + classId : name; }
    private static String when(long at) { return at > 0 ? DisplayFormat.formatTimestamp(at) : "at an unknown time"; }
    private static List<Integer> list(int[] values) { List<Integer> out = new ArrayList<>(values.length); for (int value : values) out.add(value); return List.copyOf(out); }
}
