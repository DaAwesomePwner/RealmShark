package tomato.gui.dps;

import assets.IdToAsset;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Damage;
import tomato.backend.data.DamageSource;
import tomato.backend.data.Entity;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.Evidence;

import java.util.*;

/**
 * Pure, detached projection of every retained hit for one meter row, numbered chronologically so any event
 * (for example event 1 of 1,200) is reachable through paging, not only the latest-500 text. Equipment shown
 * for an outgoing hit is the owner's loadout retained on that event; last-recorded gear is kept separate.
 * Incoming hits never record the victim's loadout, so victim gear is Not captured.
 */
public final class DamageEvents {
    private DamageEvents() { }
    public static final int PAGE_SIZE = 200;
    private static final String[] SLOTS = {"Weapon", "Ability", "Armor", "Ring"};
    private static final StatType[] INVENTORY = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};

    /** Server-reported counter flags recorded on individual hits. */
    public enum Flag {
        ORYX_GUARD("Oryx guard counter"), DAMMAH("Chancellor Dammah counter"), REFLECTOR("Walled Garden reflector");
        public final String label;
        Flag(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public static final class Event {
        /** 1-based chronological position among all retained events of this row and direction. */
        public final int number;
        public final Damage hit;
        public final boolean incoming;
        /** Milliseconds from the reference start, or null when the start is unknown. */
        public final Long offset;
        public final DamageSource source;
        public final String item, counterpart;
        public final Set<Flag> flags;
        Event(int number, Damage hit, boolean incoming, Long offset) {
            this.number = number; this.hit = hit; this.incoming = incoming; this.offset = offset;
            source = DamageSource.of(hit);
            item = incoming ? null : itemName(DamageSource.itemOf(hit));
            counterpart = incoming ? (hit.owner == null ? "AoE / ground / unknown" : name(hit.owner)) : null;
            EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
            if (hit.oryx3GuardDmg) set.add(Flag.ORYX_GUARD);
            if (hit.chancellorDammahDmg) set.add(Flag.DAMMAH);
            if (hit.walledGardenReflectors) set.add(Flag.REFLECTOR);
            flags = Collections.unmodifiableSet(set);
        }
        public int damage() { return hit.damage; }
        /** Source/item (outgoing) or attacker (incoming) text used by the item/source filter. */
        public String sourceText() { return incoming ? counterpart : source.label + (item == null ? "" : " · " + item); }
    }

    /** All filters are optional; time bounds are half-open offsets [from, until) and amounts are inclusive. */
    public static final class Filter {
        public Long fromMillis, untilMillis;
        public Integer minimum, maximum;
        public String text = "";
        public Set<DamageSource> sources = EnumSet.noneOf(DamageSource.class);
        public Set<Flag> flags = EnumSet.noneOf(Flag.class);
        public boolean matches(Event e) {
            if ((fromMillis != null || untilMillis != null) && e.offset == null) return false;
            if (fromMillis != null && e.offset < fromMillis || untilMillis != null && e.offset >= untilMillis) return false;
            if (minimum != null && e.damage() < minimum || maximum != null && e.damage() > maximum) return false;
            if (!e.incoming && !sources.isEmpty() && !sources.contains(e.source)) return false;
            if (!flags.isEmpty() && Collections.disjoint(flags, e.flags)) return false;
            String query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            return query.isEmpty() || e.sourceText().toLowerCase(Locale.ROOT).contains(query);
        }
    }

    /** Numbers every retained hit in time order (stable for equal times); {@code start} may be Long.MAX_VALUE if unknown. */
    public static List<Event> of(List<Damage> hits, boolean incoming, long start) {
        List<Damage> ordered = new ArrayList<>(hits);
        ordered.removeIf(Objects::isNull);
        ordered.sort(Comparator.comparingLong(hit -> hit.time)); // Stable: equal times keep recorded order.
        List<Event> events = new ArrayList<>(ordered.size());
        boolean known = start != Long.MAX_VALUE && start != Long.MIN_VALUE;
        for (int i = 0; i < ordered.size(); i++) events.add(new Event(i + 1, ordered.get(i), incoming, known ? ordered.get(i).time - start : null));
        return Collections.unmodifiableList(events);
    }

    public static List<Event> filter(List<Event> events, Filter filter) {
        List<Event> result = new ArrayList<>();
        for (Event event : events) if (filter == null || filter.matches(event)) result.add(event);
        return result;
    }

    public static int pages(int count, int size) { return count == 0 ? 1 : (count + size - 1) / size; }
    public static List<Event> page(List<Event> events, int page, int size) {
        int from = Math.max(0, Math.min(events.size(), page * size));
        return events.subList(from, Math.min(events.size(), from + size));
    }
    /** Page containing chronological event {@code number} in the filtered list, or -1 when filters exclude it. */
    public static int pageOf(List<Event> filtered, int number, int size) {
        for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).number == number) return i / size;
        return -1;
    }

    /** Owner event-time loadout (outgoing) or the victim's Not-captured state (incoming), plus separate last-recorded gear. */
    public static String loadout(Event e, String rowPlayer) {
        StringBuilder text = new StringBuilder();
        text.append("Event ").append(DisplayFormat.formatInteger(e.number)).append(" · ").append(e.incoming ? "Incoming" : "Outgoing")
            .append(" · ").append(DisplayFormat.formatInteger(e.damage())).append(" damage")
            .append(e.offset == null ? " · time unknown" : " · " + DisplayFormat.formatDurationSeconds(e.offset, 3) + " s").append('\n');
        if (!e.flags.isEmpty()) text.append("Flags: ").append(e.flags).append('\n');
        if (e.incoming) {
            text.append("Attacker: ").append(e.counterpart).append('\n');
            text.append("Victim (").append(rowPlayer).append(") equipment at this hit: ").append(Evidence.Coverage.NOT_CAPTURED)
                .append(". Incoming hits record no victim loadout snapshot; last-recorded or current gear is not substituted.\n");
            return text.toString();
        }
        text.append("Source: ").append(e.sourceText()).append('\n');
        text.append("Damage owner: ").append(e.hit.owner == null ? "Unattributed" : name(e.hit.owner) + " (object #" + e.hit.owner.id + ")").append('\n');
        text.append("Owner equipment retained on this event:\n");
        int[] slots = e.hit.ownerInvntory;
        if (slots == null) text.append("  ").append(Evidence.Coverage.NOT_CAPTURED).append(" for this event\n");
        else if (allEmpty(slots)) text.append("  No equipment slots recorded (summon, minion or uncaptured slots)\n");
        else for (int i = 0; i < Math.min(4, slots.length); i++) text.append("  ").append(SLOTS[i]).append(": ").append(item(slots[i])).append('\n');
        String[] enchants = e.hit.ownerEnchants;
        if (slots != null && enchants != null && enchants.length > 0) text.append("  Enchants: ").append(String.join(" · ", nonEmpty(enchants))).append('\n');
        text.append("Last recorded gear on this owner (latest known; may differ from this event):\n");
        if (e.hit.owner == null) text.append("  Unavailable (unattributed)\n");
        else for (int i = 0; i < 4; i++) {
            StatData stat = e.hit.owner.stat.get(INVENTORY[i]);
            text.append("  ").append(SLOTS[i]).append(": ").append(stat == null ? Evidence.Coverage.NOT_CAPTURED.toString() : item(stat.statValue)).append('\n');
        }
        return text.toString();
    }

    private static boolean allEmpty(int[] slots) { for (int slot : slots) if (slot != -1) return false; return true; }
    private static List<String> nonEmpty(String[] values) {
        List<String> result = new ArrayList<>(); for (String value : values) if (value != null && !value.isEmpty()) result.add(value);
        if (result.isEmpty()) result.add("None recorded"); return result;
    }
    static String item(int id) { return id == -1 ? "Empty" : Objects.toString(itemName(id), "Unknown item"); }
    static String itemName(int id) {
        if (id <= 0) return null;
        String name;
        try { name = IdToAsset.objectName(id); } catch (RuntimeException missingAssets) { name = null; }
        return name == null || name.isEmpty() ? "Item #" + id : name;
    }
    private static String name(Entity entity) {
        String name = entity.name();
        return name == null || name.isEmpty() ? "Object #" + entity.id : name;
    }
}
