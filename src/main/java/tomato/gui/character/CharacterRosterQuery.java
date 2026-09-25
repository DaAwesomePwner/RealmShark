package tomato.gui.character;

import java.util.*;
import java.util.function.IntFunction;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.RosterDefinitions;

/** Pure predicates over the complete retained roster, before view sorting. */
public final class CharacterRosterQuery {
    public enum NeedLife { ANY, YES, NO, UNKNOWN }
    public enum Missing { ANY, MISSING_STATS, COMPLETE_STATS, MISSING_CAPS }
    public enum Maxed { ANY, KNOWN_RANGE, UNKNOWN }
    public enum Age { ANY, NEWER_THAN, AT_LEAST, UNKNOWN }
    public final String text, account;
    public final Integer classId;
    public final Boolean dead, seasonal;
    public final boolean unknownSeason;
    public final NeedLife life;
    public final Missing missing;
    public final Maxed maxed;
    public final int minimum, maximum;
    public final Age age;
    public final long ageMillis;

    public CharacterRosterQuery(String text, String account, Integer classId, Boolean dead, Boolean seasonal, boolean unknownSeason,
                                NeedLife life, Missing missing, Maxed maxed, int minimum, int maximum, Age age, long ageMillis) {
        this.text = text.trim().toLowerCase(Locale.ROOT); this.account = account; this.classId = classId;
        this.dead = dead; this.seasonal = seasonal; this.unknownSeason = unknownSeason; this.life = life; this.missing = missing;
        this.maxed = maxed; this.minimum = minimum; this.maximum = maximum; this.age = age; this.ageMillis = ageMillis;
    }
    public static final class Row {
        public final CharacterRecord record;
        public final Integer maxed;
        public final Long potions;
        public final int capturedStats;
        public final boolean completeCaps;
        public final NeedLife needsLife;
        public Row(CharacterRecord record, RosterDefinitions definitions) {
            this.record = record;
            int known = 0, max = 0; long remaining = 0; boolean caps = true;
            for (int i = 0; i < 8; i++) {
                Integer value = record.stats[i], cap = definitions.cap(record.classId, i);
                if (value != null && value >= 0) known++;
                if (cap == null) caps = false;
                if (value != null && value >= 0 && cap != null) {
                    if (value >= cap) max++;
                    long deficit = Math.max(0L, (long)cap - value), step = i < 2 ? 5 : 1;
                    remaining += (deficit + step - 1) / step;
                }
            }
            capturedStats = known; completeCaps = caps;
            maxed = known == 8 && caps ? max : null; potions = known == 8 && caps ? remaining : null;
            Integer hp = record.stats[0], cap = definitions.cap(record.classId, 0);
            needsLife = hp == null || hp < 0 || cap == null ? NeedLife.UNKNOWN : hp < cap ? NeedLife.YES : NeedLife.NO;
        }
    }
    public boolean matches(Row row, long now, String accountName, IntFunction<String> itemName, IntFunction<String> className) {
        CharacterRecord r = row.record;
        if (account != null && !account.equals(r.account) || classId != null && classId != r.classId) return false;
        if (dead != null && dead != r.dead || seasonal != null && !seasonal.equals(r.seasonal) || unknownSeason && r.seasonal != null) return false;
        if (life != NeedLife.ANY && row.needsLife != life) return false;
        if (missing == Missing.MISSING_STATS && row.capturedStats == 8 || missing == Missing.COMPLETE_STATS && row.capturedStats != 8
            || missing == Missing.MISSING_CAPS && row.completeCaps) return false;
        if (maxed == Maxed.UNKNOWN && row.maxed != null || maxed == Maxed.KNOWN_RANGE && (row.maxed == null || row.maxed < minimum || row.maxed > maximum)) return false;
        if (age == Age.UNKNOWN && r.lastSeen > 0) return false;
        if (age == Age.NEWER_THAN || age == Age.AT_LEAST) {
            if (r.lastSeen <= 0) return false;
            long elapsed = Math.max(0L, now - r.lastSeen);
            if (age == Age.NEWER_THAN ? elapsed >= ageMillis : elapsed < ageMillis) return false;
        }
        StringBuilder haystack = new StringBuilder(r.characterId + " " + accountName + " " + Objects.toString(className.apply(r.classId), "") + " " + Objects.toString(r.notes, ""));
        for (Integer item : r.equipment) if (item != null && item >= 0)
            haystack.append(' ').append(item).append(" 0x").append(Integer.toHexString(item)).append(' ').append(Objects.toString(itemName.apply(item), ""));
        return haystack.toString().toLowerCase(Locale.ROOT).contains(text);
    }
}
