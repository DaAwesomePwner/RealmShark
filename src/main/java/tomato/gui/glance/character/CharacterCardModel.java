package tomato.gui.glance.character;

import java.util.Objects;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.gui.character.CharacterRosterQuery;
import tomato.realmshark.enums.CharacterClass;

/**
 * One gallery card (spec §6.2), built on the EDT from a roster row CharacterJournalGUI already projected. {@code key} is the
 * journal key the sheet opens; {@code maxed} 0–8 or null when unknown (never shown as 0); {@code seasonal} null when unknown;
 * {@code lastPlayed} the last time in game (the journal's lastObservedAlive) and {@code lastSeen} the last capture of any kind,
 * epoch ms, 0 = unknown; {@code playingNow} when {@code key} equals the in-game character's journal key (exact).
 */
public record CharacterCardModel(String key, String name, int classId, String className, Integer skin, Integer level, Long fame,
                                 Integer maxed, Boolean seasonal, long lastPlayed, long lastSeen, boolean playingNow, boolean dead) {
    public CharacterCardModel {
        Objects.requireNonNull(key, "key");
        name = name == null || name.isBlank() ? null : name;
        className = className(classId, className);
        if (maxed != null && (maxed < 0 || maxed > 8)) maxed = null;
    }

    public static CharacterCardModel of(CharacterRosterQuery.Row row, String liveKey) {
        CharacterRecord r = row.record;
        return new CharacterCardModel(r.key, r.name, r.classId, r.className, r.skin, r.level, r.fame, row.maxed, r.seasonal,
            r.lastObservedAlive, r.lastSeen, r.key.equals(liveKey), r.dead);
    }

    /** The saved class name, else the loaded class definitions' name, else "Class <id>". */
    public static String className(int classId, String saved) {
        if (saved != null && !saved.isBlank()) return saved;
        String known = CharacterClass.getName(classId);
        return known == null || known.isBlank() ? "Class " + classId : known;
    }

    public String characterId() { return key.substring(key.lastIndexOf(':') + 1); }

    /** "<name>, <class> level N, 7 of 8 maxed" (spec §10); an unknown count says so and is never read as zero. */
    public String accessibleName() {
        StringBuilder text = new StringBuilder();
        if (name != null) text.append(name).append(", ");
        text.append(className);
        if (level != null) text.append(" level ").append(level);
        text.append(maxed == null ? ", maxed stats unknown" : ", " + maxed + " of 8 maxed");
        if (playingNow) text.append(", playing now");
        if (dead) text.append(", marked dead");
        return text.toString();
    }
}
