package tomato.gui.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static tomato.gui.glance.home.HomeHistoryFixture.*;

/**
 * Synthetic saved loot for Loot Highlights, written as capture saves it (the persisted drop classes are package-private in
 * {@code tomato.gui.stats}, so drops are written as their JSON). Sessions reuse Home's fixture layout (America/New_York, one
 * local day) so Home's reader can read the same folders for the parity checks. Synthetic names only.
 */
final class HighlightsFixture {
    /** Stat potion ids (LootFacts.potionStat): small and greater Life, small Mana, small Defense, greater Defense. */
    static final int LIFE = 2793, GREATER_LIFE = 9070, MANA = 2794, DEFENSE = 2592, GREATER_DEFENSE = 9065, OTHER_POTION = 12_345;
    static final String EARLY = id("highlights-early"), LATE = id("highlights-late"), OLD = id("highlights-yesterday");
    static final int BIG_SESSIONS = 30, BIG_BAGS = 40;

    private HighlightsFixture() {}

    /** One saved item: {@code slots} null = no enchant data saved (unknown). */
    record Spec(int id, String labels, boolean potion, Integer slots, Integer applied) {}

    static Spec ut(int id, Integer slots) { return new Spec(id, "EQUIPMENT,WEAPON,UT", false, slots, slots == null ? null : 0); }
    static Spec st(int id, Integer slots) { return new Spec(id, "EQUIPMENT,ARMOR,ST", false, slots, slots == null ? null : 0); }
    static Spec tiered(int id, Integer slots) { return new Spec(id, "EQUIPMENT,WEAPON,T12", false, slots, slots == null ? null : 0); }
    static Spec potion(int id) { return new Spec(id, "STATPOTION", true, null, null); }

    /** A drop as capture saves it; {@code bag} null = a legacy drop without a bag name; {@code visit} null = none recorded. */
    static JsonObject drop(String bag, String dungeon, long time, VisitRef visit, Spec... items) {
        JsonObject drop = new JsonObject();
        if (bag != null) drop.addProperty("bag", bag);
        drop.addProperty("dungeon", dungeon);
        drop.addProperty("dropper", "Synthetic boss");
        drop.addProperty("time", time);
        JsonArray saved = new JsonArray();
        for (Spec spec : items) {
            JsonObject item = new JsonObject();
            item.addProperty("id", spec.id()); item.addProperty("name", "Item #" + spec.id());
            List<String> labels = List.of(spec.labels().split(","));
            item.addProperty("tier", labels.contains("UT") ? "UT" : labels.contains("ST") ? "ST" : labels.contains("T12") ? "T12" : "—");
            item.addProperty("potion", spec.potion());
            item.addProperty("ut", labels.contains("UT"));
            item.addProperty("st", labels.contains("ST"));
            item.addProperty("highTier", false);
            if (spec.slots() != null) {
                JsonObject enchants = new JsonObject();
                enchants.addProperty("slots", spec.slots()); enchants.addProperty("applied", spec.applied());
                item.add("enchants", enchants);
            }
            saved.add(item);
        }
        drop.add("items", saved);
        drop.addProperty("visitId", visit == null ? "" : visit.visitId);
        if (visit != null) {
            JsonObject context = new JsonObject(), ref = new JsonObject();
            ref.addProperty("sessionId", visit.sessionId); ref.addProperty("visitId", visit.visitId);
            context.addProperty("capturedAt", time); context.add("visit", ref);
            context.add("modifierIds", new JsonArray()); context.addProperty("unresolvedModifiers", 0); context.addProperty("mapRecorded", false);
            drop.add("context", context);
        }
        return drop;
    }

    /**
     * Three closed sessions: yesterday evening, early today (00:30–02:00) and late today (20:00–21:00), local day
     * {@link HomeHistoryFixture#DAY}. Today holds 6 bags: 2 UT, 2 ST, 5 potions (3 Life, 1 Defense, 1 other), 3 white bags (one
     * boosted), one legacy bag without a name, one bag in an unrecognized area and one without a map.
     */
    static void write(Path root) throws IOException {
        session(root, OLD, at(-1, 20, 0), at(-1, 22, 0));
        loot(root, OLD, drop("White", "Lost Halls", at(-1, 20, 30), ref(OLD, "o1"), ut(501, 0)));

        session(root, EARLY, at(0, 0, 30), at(0, 2, 0));
        loot(root, EARLY,
            drop("White", "Lost Halls", at(0, 0, 40), ref(EARLY, "e1"), ut(101, 0), potion(LIFE), potion(GREATER_LIFE)),
            drop("Orange", "Lost Halls", at(0, 0, 50), ref(EARLY, "e1"), tiered(102, 3), tiered(103, 1), tiered(104, null)),
            drop("B.White", "Pirate Cave", at(0, 1, 10), ref(EARLY, "e2"), st(105, 2), potion(DEFENSE)));

        session(root, LATE, at(0, 20, 0), at(0, 21, 0));
        loot(root, LATE,
            drop(null, "Lost Halls", at(0, 20, 10), null, st(201, null)),                                  // legacy: no bag name
            drop("White", "Unrecognized area", at(0, 20, 20), ref(LATE, "l1"), ut(202, 4), potion(LIFE)),
            drop("Brown", "Unknown", at(0, 20, 30), null, potion(OTHER_POTION)));
    }

    /** {@code sessions} closed sessions of 40 minutes on DAY, each with {@code bags} bags of three items (UT every 10th). */
    static void writeLarge(Path root, int sessions, int bags) throws IOException {
        String[] dungeons = {"Lost Halls", "Ice Citadel", "Pirate Cave", "Snake Pit"};
        for (int s = 0; s < sessions; s++) {
            String session = id("highlights-large-" + s);
            long start = at(0, 0, 1) + s * 45 * MINUTE;
            session(root, session, start, start + 40 * MINUTE);
            List<Object> drops = new ArrayList<>();
            for (int b = 0; b < bags; b++) {
                long time = start + b * 50_000L;
                drops.add(drop(b % 10 == 0 ? "White" : "Orange", dungeons[b % dungeons.length], time, ref(session, "L" + s + "-" + b / 4),
                    b % 10 == 0 ? ut(10_000 + b, 2) : tiered(10_000 + b, b % 3), potion(b % 2 == 0 ? LIFE : DEFENSE), st(30_000 + b, null)));
            }
            loot(root, session, drops.toArray());
        }
    }

    /** A bag as the live feed or a saved session projects it (for the model's rules without files). */
    static LootFacts.Bag bag(String bag, String dungeon, long time, VisitRef visit, LootFacts.Item... items) {
        return new LootFacts.Bag("s", time, "White".equals(bag) || "B.White".equals(bag), bag, visit, List.of(items), dungeon, "Synthetic boss");
    }
    static LootFacts.Item item(int id, boolean ut, boolean st, boolean potion, Integer slots) {
        return new LootFacts.Item(id, ut, st, false, potion, slots, slots == null ? null : 0);
    }
}
