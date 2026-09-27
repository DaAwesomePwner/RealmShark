package tomato.gui.stats;

import java.util.ArrayList;
import java.util.List;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseEnchants;

/** Real saved-drop objects (package-private LootDashboard.Drop) for fixtures in other packages. */
public final class LootTestDrops {
    public enum Kind { UT, ST, TIERED, POTION, PLAIN }
    public record Spec(int id, Kind kind) {}
    private LootTestDrops() {}

    public static Spec item(int id, Kind kind) { return new Spec(id, kind); }

    /** A drop as capture saves it; {@code visit} becomes its exact DropContext visit (null = none recorded). */
    public static Object drop(String bag, String dungeon, long time, VisitRef visit, Spec... items) {
        List<LootDashboard.Item> saved = new ArrayList<>();
        for (Spec spec : items) saved.add(spec.kind() == Kind.POTION ? new LootDashboard.Item(spec.id(), "Potion #" + spec.id(), true)
            : new LootDashboard.Item(spec.id(), "Item #" + spec.id(), labels(spec.kind()), ParseEnchants.summarize("")));
        return new LootDashboard.Drop(bag, dungeon, "Synthetic boss", time, saved, visit == null ? "" : visit.visitId,
            visit == null ? null : DropContext.capture(null, time, visit));
    }

    private static String labels(Kind kind) {
        return switch (kind) { case UT -> "EQUIPMENT,WEAPON,UT"; case ST -> "EQUIPMENT,ARMOR,ST"; case TIERED -> "EQUIPMENT,WEAPON,T13"; default -> "EQUIPMENT,RING,T4"; };
    }
}
