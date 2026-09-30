# DPS Party Outcomes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every player on a DPS recording gets an outcome (Completed, Died, Nexused, In progress or Unknown) with its time, shown in the DPS Meter (live and saved) and in the text view, while every player keeps their DPS row.

**Architecture:** Capture records a small serializable `PresenceTimeline` per encounter (players entering and leaving view, death notices, your own death and nexus, and when the dungeon ended), which rides inside `DpsData` and `DpsSnapshot`. A pure `EncounterOutcomes` turns a timeline into one outcome per player plus a summary; the meter and the text view both read it. Recordings made before this feature have no timeline and fall back to name-matched deaths.

**Tech Stack:** Java 17, Swing, JUnit 4.13.2, Gradle 7.6.4 (wrapper, offline).

**Spec:** `docs/superpowers/specs/2026-09-29-dps-party-outcomes-design.md`

## Global Constraints

- **Branch.** Work on `claude/dps-nexus-death-tracking-1274b9` (this worktree). No direct commits to `main`, no force pushes, no hook bypasses. Do not push unless the user asks.
- **Commands.** From the worktree root, every Gradle call exports the project-local JDK and Gradle home (the repository root is three levels up) and passes `--offline`. Steps abbreviate this as `GRADLE <args>`:
  ```bash
  export JAVA_HOME="$(cd ../../.. && pwd)/.tools/jdk-17.0.20.1+1" GRADLE_USER_HOME="$(cd ../../.. && pwd)/.tools/gradle-home"
  ./gradlew.bat --offline <args>
  ```
- **Validation policy (AGENTS.md, 2026-09-26).** Focused tests per task; one full `test shadowJar` and one launch smoke check at the end (Task 7). CI is not required. Never run `scripts/Set-CiDisplay.ps1`, live capture or bridge deliveries. Synthetic fixtures only.
- **Stale build outputs.** If a test class fails that has no source file, delete `build/classes/java/test`, `build/generated/sources/annotationProcessor/java/test` and `build/tmp/compileTestJava`, then rerun.
- **Commits.** Explicit paths in `git add`; imperative subject; a body saying why; trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Existing assertions.** Never weaken one. A changed call site in an existing test is reported as "replace" with the reason.
- **Serialization.** `DpsData`'s `serialVersionUID` stays `8052266513416820004L`. Every class a `.dps` file can hold must be in `EncounterImport.ALLOWED` and be resolved by the `realistic()` fixture (the allow-list test requires both). Saved classes have no `readObject`/`readResolve` hooks.
- **Threading.** The timeline is written on the packet thread only; `DpsSnapshot` and `getSaveFile` carry copies. The EDT only reads copies.
- **Honesty.** An inferred nexus is labelled as inferred in its tooltip. Unknown is never shown as Completed. Deaths are matched by name only.

## Decisions recorded for this plan (refinements of the spec)

- **Glyphs.** Labels are text ("Completed", "Died 3:12", "Nexused 2:40 · 18% HP", "In progress", "Unknown"); a death also shows its gravestone sprite. Emoji glyphs are not used because Swing fonts on Windows do not reliably render them. Color follows the text, never replaces it.
- **No column chooser.** The meter has none; the Outcome column is simply the last, sortable column.
- **In progress.** A live encounter whose end was not seen yet shows "In progress" (Kind `PENDING`) instead of "Unknown"; its tooltip says when the player left view, if they did.
- **Reconnects.** Players are grouped by name (an account name is unique in the game), so a reconnect under a new object ID is one player, not a nexus plus a newcomer.
- **Saved without an end.** A saved recording whose end was never seen has state `UNSEEN` ("end of dungeon not seen"); only deaths and your own confirmed nexus are decided.
- **Packet subscriptions.** `DEATH` and `ESCAPE` are not subscribed today; Task 4 registers both in `Tomato.packetRegister`.
- **Ordering.** Every timeline event takes the next sequence number; outcomes compare sequence numbers, never milliseconds (several events share one tick).

## File Structure

| File | Responsibility | Task |
|---|---|---|
| Create `src/main/java/packets/packetcapture/logger/CompletionDialogue.java` | Final-boss lines that prove a clear | 1 |
| Modify `src/main/java/packets/packetcapture/logger/ActivityJournal.java` | Delegate to `CompletionDialogue` | 1 |
| Create `src/main/java/tomato/backend/data/PresenceTimeline.java` | Serializable per-encounter presence record | 2 |
| Create `src/main/java/tomato/gui/dps/EncounterOutcomes.java` | Pure outcome rules, summary, party lines | 3 |
| Modify `src/main/java/tomato/backend/data/TomatoData.java` | Record the timeline, close and reset it | 4 |
| Modify `src/main/java/tomato/backend/TomatoPacketCapture.java` | Route `DeathPacket` and `EscapePacket` | 4 |
| Modify `src/main/java/tomato/Tomato.java` | Subscribe `DEATH` and `ESCAPE` | 4 |
| Modify `src/main/java/tomato/backend/data/DpsData.java` | Optional `presence` field, copied by `getSaveFile` | 4 |
| Modify `src/main/java/tomato/backend/data/DpsSnapshot.java` | Carry a timeline copy and the start time | 4 |
| Modify `src/main/java/tomato/gui/dps/EncounterImport.java` | Allow the timeline classes | 4 |
| Modify `src/main/java/tomato/gui/dps/MeterDpsGUI.java` | Outcome column, summary line, muted names | 5 |
| Modify `src/main/java/tomato/gui/dps/DpsGUI.java` | Hand the timeline to the displays | 5 |
| Modify `src/main/java/tomato/gui/dps/DpsToString.java` | Party outcome block, dungeon-level tag | 6 |
| Modify `src/main/java/tomato/gui/dps/StringDpsGUI.java` | Build outcomes for the text view | 6 |
| Modify `docs/DPS-METERS.md` | "Party outcomes" section | 7 |

Tests: `CompletionDialogueTest` (1), `PresenceTimelineTest` (2), `EncounterOutcomesTest` (3), `PresenceCaptureTest` plus the updated `EncounterImportFilterTest` (4), `MeterOutcomeTest` (5), `DpsTextOutcomeTest` plus the updated `CombatReportingTest` call (6).

---

### Task 1: Shared final-boss dialogue matcher

**Files:**
- Create: `src/main/java/packets/packetcapture/logger/CompletionDialogue.java`
- Modify: `src/main/java/packets/packetcapture/logger/ActivityJournal.java:356-371` (`completionDialogue`)
- Test: `src/test/java/packets/packetcapture/logger/CompletionDialogueTest.java`

**Interfaces:**
- Produces: `public static String CompletionDialogue.evidence(String canonicalMap, TextPacket p)` → evidence text such as `"Final boss dialogue: Void Entity"`, or `null`. `canonicalMap` is `ParseDungeon.canonicalMapName(map)`.

- [ ] **Step 1: Write the failing test**

```java
package packets.packetcapture.logger;

import org.junit.Test;
import packets.incoming.TextPacket;

import static org.junit.Assert.*;

public class CompletionDialogueTest {
    private static TextPacket line(String name, String text) {
        TextPacket p = new TextPacket(); p.name = name; p.text = text; return p;
    }

    @Test public void theVoidEntityLineMatchesOnlyInTheVoid() {
        TextPacket p = line("#Void Entity", "You fools... You can never truly defeat me! I am in all of you! I AM all of you!");
        assertEquals("Final boss dialogue: Void Entity", CompletionDialogue.evidence("The Void", p));
        assertNull(CompletionDialogue.evidence("Lost Halls", p));
    }

    @Test public void shattersAndMoonlightLinesMatch() {
        assertEquals("Final boss dialogue: King Azamoth", CompletionDialogue.evidence("The Shatters",
            line("#King Azamoth", "This fate is mine to bear... not hers.")));
        assertEquals("Final boss dialogue: Dancer Miko", CompletionDialogue.evidence("Moonlight Village",
            line("#Dancer Miko", "Thank you all for coming tonight.")));
    }

    @Test public void playerChatAndMissingFieldsNeverMatch() {
        String text = "You fools... You can never truly defeat me! I am in all of you! I AM all of you!";
        assertNull("A player quoting the line is not the boss", CompletionDialogue.evidence("The Void", line("Void Entity", text)));
        assertNull(CompletionDialogue.evidence("The Void", line("#Void Entity", null)));
        assertNull(CompletionDialogue.evidence(null, line("#Void Entity", text)));
        assertNull(CompletionDialogue.evidence("The Void", null));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `GRADLE test --tests packets.packetcapture.logger.CompletionDialogueTest`
Expected: compilation FAILS with `cannot find symbol ... CompletionDialogue`.

- [ ] **Step 3: Create `CompletionDialogue`**

```java
package packets.packetcapture.logger;

import packets.incoming.TextPacket;

/**
 * Final-boss lines that prove a dungeon was cleared where the server sends no victory notification. Shared by the activity
 * journal (run completion) and the DPS presence timeline (the end of the dungeon).
 */
public final class CompletionDialogue {
    private CompletionDialogue() { }

    /**
     * The evidence text (for example "Final boss dialogue: Void Entity") when {@code p} is a recognised final-boss line in
     * {@code map} (a canonical map name, {@code ParseDungeon.canonicalMapName}), else null. Only lines from an NPC (a name
     * starting with '#') count.
     */
    public static String evidence(String map, TextPacket p) {
        if (map == null || p == null || p.name == null || !p.name.startsWith("#") || p.text == null) return null;
        if ("Moonlight Village".equals(map) && (
                ("#Kitsune Umi".equals(p.name) && "This fully concludes the Moonlight Festival!".equals(p.text)) ||
                ("#Dancer Miko".equals(p.name) && "Thank you all for coming tonight.".equals(p.text)) ||
                ("#Umi, Goddess of Revelry".equals(p.name) && "This fully concludes the Moonlight Festival.".equals(p.text))))
            return "Final boss dialogue: " + p.name.substring(1);
        if ("The Void".equals(map) && "#Void Entity".equals(p.name)
                && "You fools... You can never truly defeat me! I am in all of you! I AM all of you!".equals(p.text))
            return "Final boss dialogue: Void Entity";
        if ("The Shatters".equals(map) && (
                ("#The Accursed King".equals(p.name) && "...do you truly think your end will be any different?".equals(p.text)) ||
                ("#King Azamoth".equals(p.name) && "This fate is mine to bear... not hers.".equals(p.text))))
            return "Final boss dialogue: " + p.name.substring(1);
        return null;
    }
}
```

- [ ] **Step 4: Make `ActivityJournal` delegate**

Replace the whole body of `private String completionDialogue(TextPacket p)` (lines 356-371) with:

```java
    private String completionDialogue(TextPacket p) {
        return current == null ? null : CompletionDialogue.evidence(current.map, p);
    }
```

- [ ] **Step 5: Run the new and the existing activity tests**

Run: `GRADLE test --tests packets.packetcapture.logger.CompletionDialogueTest --tests packets.packetcapture.logger.ActivityJournalTest --tests packets.packetcapture.logger.RunEvidenceTest`
Expected: PASS (all).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/packets/packetcapture/logger/CompletionDialogue.java src/main/java/packets/packetcapture/logger/ActivityJournal.java src/test/java/packets/packetcapture/logger/CompletionDialogueTest.java
git commit -m "Share the final-boss dialogue matcher

The DPS presence timeline needs the same clear evidence the activity
journal uses, so the matcher moves into its own class unchanged.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `PresenceTimeline`

**Files:**
- Create: `src/main/java/tomato/backend/data/PresenceTimeline.java`
- Test: `src/test/java/tomato/backend/data/PresenceTimelineTest.java`

**Interfaces:**
- Produces (all public, in `tomato.backend.data`):
  - `PresenceTimeline()`; recording: `recordSeen(int objectId, String name, int classType, long at, boolean local)`, `recordLeft(int objectId, int hp, int maxHp, long at)`, `recordDeath(String name, int graveIcon, long at)`, `recordLocalDeath(String killedBy, long at)`, `recordEscape(long at)`, `recordEnd(String source, long at)`, `copy()`.
  - reading: `Map<Integer, Player> players()`, `List<Death> deaths()`, `Integer localObjectId()`, `Mark localDeath()`, `Mark localEscape()`, `Mark end()`.
  - constants `END_VICTORY = "victory"`, `END_DIALOGUE = "dialogue"`, `END_BOSS = "boss"`.
  - nested: `Player { final int objectId; String name; int classType; final ArrayList<Change> changes; boolean present(); }`, `Change { final long seq, at; final boolean present; final int hp, maxHp; }`, `Death { final long seq, at; final String name; final int graveIcon; }`, `Mark { final long seq, at; final String detail; }`.

- [ ] **Step 1: Write the failing test**

```java
package tomato.backend.data;

import org.junit.Test;

import java.io.*;

import static org.junit.Assert.*;

public class PresenceTimelineTest {
    @Test public void seeingAPlayerTwiceAddsOneEntryAndLeavingAddsOneExit() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(5, "Ann", 768, 100, false);
        t.recordSeen(5, null, 0, 110, false);          // a repeated sighting changes nothing
        t.recordLeft(5, 350, 700, 200);
        t.recordLeft(5, 300, 700, 210);                // already gone
        PresenceTimeline.Player ann = t.players().get(5);
        assertEquals("Ann", ann.name); assertEquals(768, ann.classType);
        assertEquals(2, ann.changes.size());
        assertFalse(ann.present());
        assertEquals(350, ann.changes.get(1).hp);
        assertTrue("Sequence numbers order events", ann.changes.get(0).seq < ann.changes.get(1).seq);
        t.recordSeen(5, "Ann", 768, 300, false);
        assertTrue(ann.present()); assertEquals(3, ann.changes.size());
    }

    @Test public void aLeaveForSomeoneNeverSeenStillCounts() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordLeft(9, 10, 100, 50);
        assertFalse(t.players().get(9).present());
        assertNull(t.players().get(9).name);
    }

    @Test public void theEndPrefersVictoryThenDialogueThenTheLastBossRemoved() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordEnd(PresenceTimeline.END_BOSS, 100);
        t.recordEnd(PresenceTimeline.END_BOSS, 200);
        assertEquals(200, t.end().at);                 // the last boss removed wins among bosses
        t.recordEnd(PresenceTimeline.END_DIALOGUE, 300);
        t.recordEnd(PresenceTimeline.END_BOSS, 400);   // weaker: ignored
        assertEquals(PresenceTimeline.END_DIALOGUE, t.end().detail); assertEquals(300, t.end().at);
        t.recordEnd(PresenceTimeline.END_VICTORY, 500);
        t.recordEnd(PresenceTimeline.END_VICTORY, 600); // the first victory stays
        assertEquals(PresenceTimeline.END_VICTORY, t.end().detail); assertEquals(500, t.end().at);
        t.recordEnd("something else", 700);
        assertEquals(500, t.end().at);
    }

    @Test public void yourDeathAndNexusKeepTheFirstEvent() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 0, true);
        t.recordEscape(10); t.recordEscape(20);
        t.recordLocalDeath("Foe", 30); t.recordLocalDeath("Other", 40);
        assertEquals(Integer.valueOf(1), t.localObjectId());
        assertEquals(10, t.localEscape().at);
        assertEquals("Foe", t.localDeath().detail);
    }

    @Test public void aCopyIsIndependentOfLaterRecording() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Ann", 768, 0, false);
        PresenceTimeline copy = t.copy();
        t.recordLeft(1, 0, 700, 10); t.recordDeath("Ann", 0x0723, 10); t.recordEnd(PresenceTimeline.END_VICTORY, 20);
        assertTrue(copy.players().get(1).present());
        assertTrue(copy.deaths().isEmpty()); assertNull(copy.end());
        copy.recordSeen(2, "Bob", 775, 30, false);
        assertFalse("Sequence numbers continue in the copy", t.players().containsKey(2));
        assertTrue(copy.players().get(2).changes.get(0).seq > copy.players().get(1).changes.get(0).seq);
    }

    @Test public void itSurvivesJavaSerialization() throws Exception {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Ann", 768, 0, true); t.recordLeft(1, 5, 10, 1); t.recordDeath("Ann", 3, 2);
        t.recordEscape(3); t.recordLocalDeath("Foe", 4); t.recordEnd(PresenceTimeline.END_VICTORY, 5);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(t); }
        PresenceTimeline read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { read = (PresenceTimeline) in.readObject(); }
        assertEquals("Ann", read.players().get(1).name);
        assertEquals(1, read.deaths().size()); assertEquals("Foe", read.localDeath().detail);
        assertEquals(PresenceTimeline.END_VICTORY, read.end().detail);
        read.recordSeen(2, "Bob", 775, 6, false);
        assertTrue(read.players().get(2).changes.get(0).seq > read.end().seq);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `GRADLE test --tests tomato.backend.data.PresenceTimelineTest`
Expected: compilation FAILS with `cannot find symbol ... PresenceTimeline`.

- [ ] **Step 3: Create `PresenceTimeline`**

```java
package tomato.backend.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who was in a logged area and when (docs/DPS-METERS.md, "Party outcomes"): players entering and leaving view, death
 * notices, your own death and nexus, and when the dungeon ended. Written on the packet thread only; published as copies.
 * Every event takes the next sequence number, which orders events exactly (several share one tick); times are capture
 * wall-clock milliseconds ({@code TomatoData.timePc}), -1 before the area's first tick. Plain data with no deserialization
 * hooks: {@code EncounterImport} reads it through its allow-list.
 */
public final class PresenceTimeline implements Serializable {
    private static final long serialVersionUID = 1L;
    /** How the end of the dungeon was observed, strongest first. */
    public static final String END_VICTORY = "victory", END_DIALOGUE = "dialogue", END_BOSS = "boss";

    /** A player entering ({@code present}) or leaving view; when leaving, the HP last seen (0 when unknown). */
    public static final class Change implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long seq, at;
        public final boolean present;
        public final int hp, maxHp;
        Change(long seq, long at, boolean present, int hp, int maxHp) {
            this.seq = seq; this.at = at; this.present = present; this.hp = hp; this.maxHp = maxHp;
        }
    }

    /** One object ID's presence; the name and class are the last known. */
    public static final class Player implements Serializable {
        private static final long serialVersionUID = 1L;
        public final int objectId;
        public String name;
        public int classType;
        public final ArrayList<Change> changes = new ArrayList<>();
        Player(int objectId) { this.objectId = objectId; }
        /** In view after its last change. */
        public boolean present() { return !changes.isEmpty() && changes.get(changes.size() - 1).present; }
    }

    /** A death notice: the name it carried (null when it could not be read) and the gravestone. */
    public static final class Death implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long seq, at;
        public final String name;
        public final int graveIcon;
        Death(long seq, long at, String name, int graveIcon) { this.seq = seq; this.at = at; this.name = name; this.graveIcon = graveIcon; }
    }

    /** A single observed moment with an optional detail (the killer, or how the end was observed). */
    public static final class Mark implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long seq, at;
        public final String detail;
        Mark(long seq, long at, String detail) { this.seq = seq; this.at = at; this.detail = detail; }
    }

    private long seq;
    private final HashMap<Integer, Player> players = new HashMap<>();
    private final ArrayList<Death> deaths = new ArrayList<>();
    private Integer localObjectId;
    private Mark localDeath, localEscape, end;

    /** A player object arrived in view (or is the local player); a repeated sighting only refreshes its name and class. */
    public void recordSeen(int objectId, String name, int classType, long at, boolean local) {
        Player player = players.computeIfAbsent(objectId, Player::new);
        if (name != null && !name.isEmpty()) player.name = name;
        if (classType > 0) player.classType = classType;
        if (local) localObjectId = objectId;
        if (!player.present()) player.changes.add(new Change(++seq, at, true, 0, 0));
    }

    /** A player object left view with the HP last seen; ignored when it is already out of view. */
    public void recordLeft(int objectId, int hp, int maxHp, long at) {
        Player player = players.computeIfAbsent(objectId, Player::new);
        if (player.changes.isEmpty() || player.present()) player.changes.add(new Change(++seq, at, false, hp, maxHp));
    }

    public void recordDeath(String name, int graveIcon, long at) {
        deaths.add(new Death(++seq, at, name == null || name.isEmpty() ? null : name, graveIcon));
    }

    /** Your own death (the DEATH packet); the first one stays. */
    public void recordLocalDeath(String killedBy, long at) {
        if (localDeath == null) localDeath = new Mark(++seq, at, killedBy);
    }

    /** You pressed nexus (the outgoing ESCAPE packet); the first one stays. */
    public void recordEscape(long at) {
        if (localEscape == null) localEscape = new Mark(++seq, at, null);
    }

    /**
     * The dungeon ended, observed as {@code source}: a stronger source replaces a weaker one, a later boss removal replaces
     * an earlier one, and the first victory or dialogue stays. Unknown sources are ignored.
     */
    public void recordEnd(String source, long at) {
        int rank = rank(source);
        if (rank == 0) return;
        int current = end == null ? 0 : rank(end.detail);
        if (rank > current || rank == current && END_BOSS.equals(source)) end = new Mark(++seq, at, source);
    }

    private static int rank(String source) {
        return END_VICTORY.equals(source) ? 3 : END_DIALOGUE.equals(source) ? 2 : END_BOSS.equals(source) ? 1 : 0;
    }

    /** A detached copy: later recording on either side does not reach the other. */
    public PresenceTimeline copy() {
        PresenceTimeline copy = new PresenceTimeline();
        copy.seq = seq;
        players.forEach((id, player) -> {
            Player detached = new Player(id);
            detached.name = player.name; detached.classType = player.classType; detached.changes.addAll(player.changes);
            copy.players.put(id, detached);
        });
        copy.deaths.addAll(deaths);
        copy.localObjectId = localObjectId; copy.localDeath = localDeath; copy.localEscape = localEscape; copy.end = end;
        return copy;
    }

    public Map<Integer, Player> players() { return Collections.unmodifiableMap(players); }
    public List<Death> deaths() { return Collections.unmodifiableList(deaths); }
    public Integer localObjectId() { return localObjectId; }
    public Mark localDeath() { return localDeath; }
    public Mark localEscape() { return localEscape; }
    public Mark end() { return end; }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE test --tests tomato.backend.data.PresenceTimelineTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/backend/data/PresenceTimeline.java src/test/java/tomato/backend/data/PresenceTimelineTest.java
git commit -m "Add a per-encounter presence timeline

Deciding who completed a dungeon needs to know when each player left
view, came back or died and when the dungeon ended; this is the
serializable record capture will fill.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `EncounterOutcomes`

**Files:**
- Create: `src/main/java/tomato/gui/dps/EncounterOutcomes.java`
- Test: `src/test/java/tomato/gui/dps/EncounterOutcomesTest.java`

**Interfaces:**
- Consumes: `PresenceTimeline` (Task 2), `DeathParser.extractName(NotificationPacket)`.
- Produces (public, `tomato.gui.dps`):
  - `enum Kind { COMPLETED, PENDING, NEXUSED, DIED, UNKNOWN }`, `enum State { AVAILABLE, PENDING, UNSEEN, UNAVAILABLE }`
  - `static EncounterOutcomes of(PresenceTimeline presence, long startedAt, boolean live, Collection<Entity> players, Collection<NotificationPacket> notices)`. `players`/`notices` are used only when `presence` is null (legacy).
  - `static EncounterOutcomes none()`, `static List<Entity> playersOf(Collection<Entity> enemies)`
  - `Outcome outcome(int objectId)`, `Outcome outcome(Entity player)`, `State state()`, `List<Line> lines()`, `String summary()`, counts `players() completed() nexused() died() unknown() pending()`
  - `Outcome { final Kind kind; final Long atMs; final Integer hpPercent; final int graveIcon; final String killedBy; final boolean confirmed; final String reason; String label(); boolean didNotComplete(); }` (Comparable, `toString()` = `label()`)
  - `Line { final String name; final int classType; final Outcome outcome; }`

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.NotificationEffectType;
import packets.data.enums.StatType;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.backend.data.Projectile;
import tomato.gui.dps.EncounterOutcomes.Kind;
import tomato.gui.dps.EncounterOutcomes.Outcome;
import tomato.gui.dps.EncounterOutcomes.State;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class EncounterOutcomesTest {
    private static final long START = 1_000;

    private static EncounterOutcomes saved(PresenceTimeline t) { return EncounterOutcomes.of(t, START, false, List.of(), List.of()); }

    static Entity named(int id, String name, int type) {
        Entity player = new Entity(null, id, 0); player.objectType = type;
        StatData stat = new StatData(); stat.statType = StatType.NAME_STAT; stat.statTypeNum = StatType.NAME_STAT.get(); stat.stringStatValue = name;
        player.stat.set(StatType.NAME_STAT, stat);
        return player;
    }

    static NotificationPacket death(String name, int grave) {
        NotificationPacket n = new NotificationPacket(); n.effect = NotificationEffectType.PlayerDeath; n.pictureType = grave;
        n.message = "{\"k\":\"s.death\",\"t\":{\"player\":\"" + name + "\",\"level\":\"20\"}}";
        return n;
    }

    @Test public void presentAtTheVictoryCompletesAndWalkingOutOfViewAndBackIsNotANexus() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Stayer", 768, 1_000, true);
        t.recordSeen(2, "Wanderer", 775, 1_000, false);
        t.recordLeft(2, 700, 700, 20_000); t.recordSeen(2, "Wanderer", 775, 30_000, false);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        EncounterOutcomes o = saved(t);
        assertEquals(State.AVAILABLE, o.state());
        assertEquals(Kind.COMPLETED, o.outcome(1).kind);
        assertTrue("Your own completion is observed directly", o.outcome(1).confirmed);
        assertEquals(Kind.COMPLETED, o.outcome(2).kind);
        assertEquals("In the dungeon when it ended (server victory).", o.outcome(2).reason);
        assertEquals("Completed", o.outcome(2).label());
        assertEquals("2 players · 2 completed", o.summary());
    }

    @Test public void leavingBeforeTheEndWithoutReturningIsAnInferredNexusWithTimeAndHp() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(2, "Quitter", 775, 1_000, false);
        t.recordLeft(2, 126, 700, 161_000);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        Outcome q = saved(t).outcome(2);
        assertEquals(Kind.NEXUSED, q.kind); assertFalse(q.confirmed);
        assertEquals(Long.valueOf(160_000), q.atMs); assertEquals(Integer.valueOf(18), q.hpPercent);
        assertEquals("Nexused 2:40 · 18% HP", q.label());
        assertTrue(q.reason, q.reason.contains("a nexus, a disconnect, or out of view when it ended"));
        assertTrue(q.didNotComplete());
    }

    @Test public void leavingOrDyingAfterTheEndStillCompletes() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(2, "Leaver", 775, 1_000, false); t.recordSeen(3, "Late", 768, 1_000, false);
        t.recordEnd(PresenceTimeline.END_BOSS, 100_000);
        t.recordLeft(2, 700, 700, 120_000); t.recordDeath("Late", 0x0723, 130_000);
        EncounterOutcomes o = saved(t);
        assertEquals(Kind.COMPLETED, o.outcome(2).kind); assertEquals(Kind.COMPLETED, o.outcome(3).kind);
        assertEquals("In the dungeon when it ended (last boss removed).", o.outcome(2).reason);
        assertEquals("2 players · 2 completed", o.summary());
    }

    @Test public void aDeathNoticeBeforeTheEndIsADeathWithItsTimeAndGravestone() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(3, "Faller", 768, 1_000, false);
        t.recordDeath("Faller", 0x0723, 61_000); t.recordLeft(3, 0, 700, 61_000);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        Outcome f = saved(t).outcome(3);
        assertEquals(Kind.DIED, f.kind); assertEquals(Long.valueOf(60_000), f.atMs);
        assertEquals(0x0723, f.graveIcon); assertEquals("Died 1:00", f.label()); assertTrue(f.didNotComplete());
        assertEquals("1 player · 0 completed · 1 died", saved(t).summary());
    }

    @Test public void withoutAnEndALiveEncounterIsPendingAndASavedOneIsUnseen() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 1_000, true); t.recordSeen(2, "Away", 775, 1_000, false);
        t.recordLeft(2, 350, 700, 50_000);
        EncounterOutcomes live = EncounterOutcomes.of(t, START, true, List.of(), List.of());
        assertEquals(State.PENDING, live.state());
        assertEquals(Kind.PENDING, live.outcome(1).kind); assertEquals("In progress", live.outcome(1).label());
        assertEquals("Left view at 0:49; decided when the dungeon ends.", live.outcome(2).reason);
        assertEquals("2 players · outcome pending", live.summary());
        EncounterOutcomes saved = saved(t);
        assertEquals(State.UNSEEN, saved.state());
        assertEquals(Kind.UNKNOWN, saved.outcome(2).kind); assertFalse(saved.outcome(2).didNotComplete());
        assertEquals("2 players · end of dungeon not seen", saved.summary());
    }

    @Test public void yourOwnNexusIsConfirmedEvenThoughTheEndWasNeverSeen() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 1_000, true); t.recordEscape(90_000);
        Outcome mine = saved(t).outcome(1);
        assertEquals(Kind.NEXUSED, mine.kind); assertTrue(mine.confirmed);
        assertEquals("Nexused 1:29", mine.label()); assertEquals("You pressed nexus at 1:29.", mine.reason);
        assertEquals("1 player · 1 nexused · end of dungeon not seen", saved(t).summary());
    }

    @Test public void yourDeathPacketNamesTheKiller() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 1_000, true);
        t.recordDeath("Self", 0x0723, 40_000); t.recordLocalDeath("Synthetic foe", 40_000);
        Outcome mine = saved(t).outcome(1);
        assertEquals(Kind.DIED, mine.kind); assertTrue(mine.confirmed);
        assertEquals("Synthetic foe", mine.killedBy); assertEquals(0x0723, mine.graveIcon);
        assertEquals("You died at 0:39, killed by Synthetic foe.", mine.reason);
    }

    @Test public void aReconnectUnderANewObjectIdIsTheSamePlayer() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(5, "Flaky", 768, 1_000, false); t.recordLeft(5, 700, 700, 40_000);
        t.recordSeen(6, "Flaky", 768, 45_000, false);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        EncounterOutcomes o = saved(t);
        assertEquals(Kind.COMPLETED, o.outcome(5).kind); assertSame(o.outcome(5), o.outcome(6));
        assertEquals(1, o.lines().size()); assertEquals("1 player · 1 completed", o.summary());
    }

    @Test public void aDeathNoticeForSomeoneNeverSeenStillCountsAsADeath() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Seen", 768, 1_000, false); t.recordDeath("Stranger", 0x0723, 20_000);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        EncounterOutcomes o = saved(t);
        assertEquals(1, o.lines().size()); assertEquals("2 players · 1 completed · 1 died", o.summary());
    }

    @Test public void someoneFirstSeenAfterTheEndOrNeverSeenIsUnknown() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordEnd(PresenceTimeline.END_VICTORY, 100_000);
        t.recordSeen(7, "Late", 768, 120_000, false);
        EncounterOutcomes o = saved(t);
        assertEquals(Kind.UNKNOWN, o.outcome(7).kind); assertEquals("First seen after the dungeon ended.", o.outcome(7).reason);
        assertEquals(Kind.UNKNOWN, o.outcome(99).kind);
        assertEquals("1 player · 0 completed · 1 unknown", o.summary());
    }

    @Test public void aRecordingWithoutATimelineShowsOnlyDeathsMatchedByAUniqueName() {
        Entity alice = named(1, "Alice", 768), bob = named(2, "Bob", 775), twin = named(3, "Twin", 768), other = named(4, "Twin", 782);
        EncounterOutcomes o = EncounterOutcomes.of(null, -1, false, Arrays.asList(alice, bob, twin, other),
            Arrays.asList(death("Bob", 0x0723), death("Twin", 0x0723)));
        assertEquals(State.UNAVAILABLE, o.state());
        assertEquals(Kind.UNKNOWN, o.outcome(alice).kind);
        assertEquals(Kind.DIED, o.outcome(bob).kind); assertEquals("Died", o.outcome(bob).label());
        assertEquals("A shared name is never matched", Kind.UNKNOWN, o.outcome(twin).kind);
        assertEquals("Outcomes unavailable (recorded before this feature)", o.summary());
        assertEquals(Kind.UNKNOWN, EncounterOutcomes.none().outcome(99).kind);
        assertEquals(Kind.UNKNOWN, EncounterOutcomes.none().outcome((Entity) null).kind);
    }

    @Test public void playersOfListsEveryDamagingPlayerOnce() {
        Entity alice = named(1, "Alice", 768), bob = named(2, "Bob", 775);
        Entity enemy = new Entity(null, 11, 0), other = new Entity(null, 12, 0);
        enemy.genericDamageHit(alice, new Projectile(100), 1000); enemy.genericDamageHit(bob, new Projectile(50), 1100);
        other.genericDamageHit(alice, new Projectile(10), 1200);
        List<Entity> players = EncounterOutcomes.playersOf(Arrays.asList(enemy, other, null));
        assertEquals(2, players.size());
        assertTrue(players.contains(alice)); assertTrue(players.contains(bob));
        assertTrue(EncounterOutcomes.playersOf(Collections.emptyList()).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `GRADLE test --tests tomato.gui.dps.EncounterOutcomesTest`
Expected: compilation FAILS with `cannot find symbol ... EncounterOutcomes`.

- [ ] **Step 3: Create `EncounterOutcomes`**

```java
package tomato.gui.dps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.gui.dps.shared.DeathParser;

/**
 * Whether each player of a recording completed the dungeon, died, nexused, is still in progress, or is unknown
 * (docs/DPS-METERS.md, "Party outcomes"). Built from the recording's {@link PresenceTimeline}; players are grouped by name,
 * since an account name is unique in the game and a reconnect gives the same player a new object ID. A recording made before
 * outcome tracking has no timeline: only a death notice naming a unique player is shown. Immutable; build it on any thread
 * from copies.
 */
public final class EncounterOutcomes {
    /** Sort order in the meter: completed first, unknown last. */
    public enum Kind { COMPLETED, PENDING, NEXUSED, DIED, UNKNOWN }
    /** AVAILABLE: the end was seen. PENDING: live, not ended yet. UNSEEN: saved, the end was never seen. UNAVAILABLE: no timeline. */
    public enum State { AVAILABLE, PENDING, UNSEEN, UNAVAILABLE }

    static final String LEGACY = "Recorded before outcome tracking: only a death notice naming this player can be shown.";
    static final String PENDING_REASON = "The dungeon has not ended yet; outcomes are decided when it does.";
    static final String UNSEEN_REASON = "The end of the dungeon was not seen (you left first, or it sent no victory, final-boss line or boss removal).";
    static final String NOT_SEEN = "Not seen entering this area (capture started or restarted mid-dungeon).";

    /** One player's outcome. {@code toString} is the label, so the meter's sorter and copy show it. */
    public static final class Outcome implements Comparable<Outcome> {
        public final Kind kind;
        /** Milliseconds from the encounter start, or null when unknown. */
        public final Long atMs;
        /** Whole-percent HP when the player left view, or null. */
        public final Integer hpPercent;
        /** Gravestone object type for a death, or -1. */
        public final int graveIcon;
        /** Your own death only: what killed you, when the server said. */
        public final String killedBy;
        /** Observed directly (your own nexus, death or presence), not inferred from someone leaving view. */
        public final boolean confirmed;
        /** One sentence saying how the outcome was decided. */
        public final String reason;

        Outcome(Kind kind, Long atMs, Integer hpPercent, int graveIcon, String killedBy, boolean confirmed, String reason) {
            this.kind = kind; this.atMs = atMs; this.hpPercent = hpPercent; this.graveIcon = graveIcon;
            this.killedBy = killedBy; this.confirmed = confirmed; this.reason = reason;
        }

        public String label() {
            switch (kind) {
                case COMPLETED: return "Completed";
                case PENDING: return "In progress";
                case DIED: return atMs == null ? "Died" : "Died " + clock(atMs);
                case NEXUSED: {
                    StringBuilder text = new StringBuilder("Nexused");
                    if (atMs != null) text.append(' ').append(clock(atMs));
                    if (hpPercent != null) text.append(" · ").append(hpPercent).append("% HP");
                    return text.toString();
                }
                default: return "Unknown";
            }
        }

        /** Died or nexused: the player is marked as not having completed the dungeon. */
        public boolean didNotComplete() { return kind == Kind.DIED || kind == Kind.NEXUSED; }

        @Override public int compareTo(Outcome other) {
            int byKind = Integer.compare(kind.ordinal(), other.kind.ordinal());
            if (byKind != 0) return byKind;
            return Long.compare(atMs == null ? Long.MAX_VALUE : atMs, other.atMs == null ? Long.MAX_VALUE : other.atMs);
        }

        @Override public String toString() { return label(); }
    }

    /** One player of the party list: the name shown, the class and the outcome. */
    public static final class Line {
        public final String name;
        public final int classType;
        public final Outcome outcome;
        Line(String name, int classType, Outcome outcome) { this.name = name; this.classType = classType; this.outcome = outcome; }
    }

    private static final Outcome LEGACY_UNKNOWN = new Outcome(Kind.UNKNOWN, null, null, -1, null, false, LEGACY);
    private static final Outcome NOT_SEEN_OUTCOME = new Outcome(Kind.UNKNOWN, null, null, -1, null, false, NOT_SEEN);
    private static final EncounterOutcomes NONE = new EncounterOutcomes(State.UNAVAILABLE, Collections.emptyMap(), Collections.emptyList(), 0);

    private final State state;
    private final Map<Integer, Outcome> byId;
    private final List<Line> lines;
    private final int players, completed, nexused, died, unknown, pending;

    private EncounterOutcomes(State state, Map<Integer, Outcome> byId, List<Line> lines, int unmatchedDeaths) {
        this.state = state;
        this.byId = byId;
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing((Line line) -> line.outcome).thenComparing(line -> line.name, String.CASE_INSENSITIVE_ORDER));
        this.lines = Collections.unmodifiableList(sorted);
        int c = 0, n = 0, d = unmatchedDeaths, u = 0, p = 0;
        for (Line line : sorted) {
            switch (line.outcome.kind) {
                case COMPLETED: c++; break;
                case NEXUSED: n++; break;
                case DIED: d++; break;
                case PENDING: p++; break;
                default: u++;
            }
        }
        players = sorted.size() + unmatchedDeaths; completed = c; nexused = n; died = d; unknown = u; pending = p;
    }

    /** No players and no timeline. */
    public static EncounterOutcomes none() { return NONE; }

    /**
     * Outcomes of one recording. {@code startedAt} is its first tick ({@code DpsData.dungeonStartTime}); {@code live} marks
     * an encounter still being recorded. {@code players} and {@code notices} are read only when {@code presence} is null.
     */
    public static EncounterOutcomes of(PresenceTimeline presence, long startedAt, boolean live,
                                       Collection<Entity> players, Collection<NotificationPacket> notices) {
        return presence == null ? legacy(players, notices) : observed(presence, startedAt, live);
    }

    /** Distinct player characters with recorded damage on these enemies, first seen first. */
    public static List<Entity> playersOf(Collection<Entity> enemies) {
        Map<Integer, Entity> players = new LinkedHashMap<>();
        if (enemies != null) for (Entity enemy : enemies) {
            if (enemy == null) continue;
            for (Damage hit : enemy.getPlayerDamageList()) if (hit != null && hit.owner != null) players.putIfAbsent(hit.owner.id, hit.owner);
        }
        return new ArrayList<>(players.values());
    }

    private static EncounterOutcomes observed(PresenceTimeline timeline, long startedAt, boolean live) {
        PresenceTimeline.Mark end = timeline.end();
        List<PresenceTimeline.Player> ordered = new ArrayList<>(timeline.players().values());
        ordered.sort(Comparator.comparingInt(player -> player.objectId));
        Map<String, List<PresenceTimeline.Player>> groups = new LinkedHashMap<>();
        for (PresenceTimeline.Player player : ordered)
            groups.computeIfAbsent(player.name == null ? "#" + player.objectId : "n:" + player.name, key -> new ArrayList<>()).add(player);
        Map<String, PresenceTimeline.Death> deaths = new HashMap<>();
        int unmatched = 0;
        for (PresenceTimeline.Death death : timeline.deaths()) {
            if (death.name != null && groups.containsKey("n:" + death.name)) deaths.putIfAbsent(death.name, death);
            else if (before(death.seq, end)) unmatched++;
        }
        Integer local = timeline.localObjectId();
        Map<Integer, Outcome> byId = new HashMap<>();
        List<Line> lines = new ArrayList<>();
        for (List<PresenceTimeline.Player> group : groups.values()) {
            String name = group.get(0).name;
            boolean mine = false;
            int classType = 0;
            List<PresenceTimeline.Change> changes = new ArrayList<>();
            for (PresenceTimeline.Player player : group) {
                mine |= local != null && player.objectId == local;
                if (player.classType > 0) classType = player.classType;
                changes.addAll(player.changes);
            }
            changes.sort(Comparator.comparingLong(change -> change.seq));
            Outcome outcome = decide(timeline, end, mine, name == null ? null : deaths.get(name), changes, startedAt, live);
            for (PresenceTimeline.Player player : group) byId.put(player.objectId, outcome);
            lines.add(new Line(name == null ? "Unknown player" : name, classType, outcome));
        }
        State state = end != null ? State.AVAILABLE : live ? State.PENDING : State.UNSEEN;
        return new EncounterOutcomes(state, byId, lines, unmatched);
    }

    private static Outcome decide(PresenceTimeline timeline, PresenceTimeline.Mark end, boolean mine, PresenceTimeline.Death death,
                                  List<PresenceTimeline.Change> changes, long startedAt, boolean live) {
        PresenceTimeline.Mark localDeath = mine ? timeline.localDeath() : null, escape = mine ? timeline.localEscape() : null;
        int grave = death == null ? -1 : death.graveIcon;
        if (localDeath != null && before(localDeath.seq, end)) {
            Long at = since(localDeath.at, startedAt);
            return new Outcome(Kind.DIED, at, null, grave, localDeath.detail, true,
                "You died" + when(at) + (localDeath.detail == null ? "." : ", killed by " + localDeath.detail + "."));
        }
        if (death != null && before(death.seq, end)) {
            Long at = since(death.at, startedAt);
            return new Outcome(Kind.DIED, at, null, grave, null, false, "A death notice named this player" + when(at) + ".");
        }
        if (escape != null && before(escape.seq, end)) {
            Long at = since(escape.at, startedAt);
            return new Outcome(Kind.NEXUSED, at, null, -1, null, true, "You pressed nexus" + when(at) + ".");
        }
        if (end == null) return live ? pending(changes, startedAt) : unknown(UNSEEN_REASON);
        if (changes.isEmpty()) return NOT_SEEN_OUTCOME;
        if (changes.get(0).seq > end.seq) return unknown("First seen after the dungeon ended.");
        PresenceTimeline.Change last = changes.get(changes.size() - 1);
        if (!last.present && last.seq < end.seq) {
            Long at = since(last.at, startedAt);
            Integer hp = last.maxHp > 0 ? (int) Math.round(100.0 * Math.max(0, Math.min(last.hp, last.maxHp)) / last.maxHp) : null;
            return new Outcome(Kind.NEXUSED, at, hp, -1, null, false, "Left view" + when(at) + (hp == null ? "" : " at " + hp + "% HP")
                + " and did not return before the dungeon ended: a nexus, a disconnect, or out of view when it ended.");
        }
        return new Outcome(Kind.COMPLETED, since(end.at, startedAt), null, -1, null, mine,
            "In the dungeon when it ended (" + endLabel(end.detail) + ").");
    }

    private static Outcome pending(List<PresenceTimeline.Change> changes, long startedAt) {
        PresenceTimeline.Change last = changes.isEmpty() ? null : changes.get(changes.size() - 1);
        String reason = last != null && !last.present
            ? "Left view" + when(since(last.at, startedAt)) + "; decided when the dungeon ends." : PENDING_REASON;
        return new Outcome(Kind.PENDING, null, null, -1, null, false, reason);
    }

    private static EncounterOutcomes legacy(Collection<Entity> players, Collection<NotificationPacket> notices) {
        List<Entity> people = players == null ? Collections.emptyList() : new ArrayList<>(players);
        Map<String, Integer> names = new HashMap<>();
        for (Entity player : people) { String name = name(player); if (name != null) names.merge(name, 1, Integer::sum); }
        Map<String, NotificationPacket> deaths = new HashMap<>();
        if (notices != null) for (NotificationPacket notice : new ArrayList<>(notices)) {
            String name = notice == null ? null : DeathParser.extractName(notice);
            if (name != null && !name.isEmpty()) deaths.putIfAbsent(name, notice);
        }
        Map<Integer, Outcome> byId = new HashMap<>();
        List<Line> lines = new ArrayList<>();
        for (Entity player : people) {
            if (player == null) continue;
            String name = name(player);
            NotificationPacket death = name != null && names.get(name) == 1 ? deaths.get(name) : null;
            Outcome outcome = death == null ? LEGACY_UNKNOWN : new Outcome(Kind.DIED, null, null, death.pictureType, null, false,
                "A death notice names this player; recorded before outcome tracking, so no time is known.");
            byId.put(player.id, outcome);
            lines.add(new Line(name == null ? "Unknown player" : name, player.objectType, outcome));
        }
        return new EncounterOutcomes(State.UNAVAILABLE, byId, lines, 0);
    }

    public Outcome outcome(int objectId) {
        Outcome outcome = byId.get(objectId);
        return outcome != null ? outcome : state == State.UNAVAILABLE ? LEGACY_UNKNOWN : NOT_SEEN_OUTCOME;
    }

    public Outcome outcome(Entity player) { return player == null ? outcome(Integer.MIN_VALUE) : outcome(player.id); }

    public State state() { return state; }
    /** One line per player, completed first, then by name. */
    public List<Line> lines() { return lines; }
    public int players() { return players; }
    public int completed() { return completed; }
    public int nexused() { return nexused; }
    public int died() { return died; }
    public int unknown() { return unknown; }
    public int pending() { return pending; }

    /** "8 players · 5 completed · 2 nexused · 1 died", or the pending, unseen or unavailable form. */
    public String summary() {
        if (state == State.UNAVAILABLE) return "Outcomes unavailable (recorded before this feature)";
        StringBuilder text = new StringBuilder().append(players).append(players == 1 ? " player" : " players");
        if (state == State.AVAILABLE) text.append(" · ").append(completed).append(" completed");
        if (nexused > 0) text.append(" · ").append(nexused).append(" nexused");
        if (died > 0) text.append(" · ").append(died).append(" died");
        if (state == State.PENDING) text.append(" · outcome pending");
        else if (state == State.UNSEEN) text.append(" · end of dungeon not seen");
        else if (unknown > 0) text.append(" · ").append(unknown).append(" unknown");
        return text.toString();
    }

    private static boolean before(long seq, PresenceTimeline.Mark end) { return end == null || seq < end.seq; }

    private static Long since(long at, long startedAt) { return at < 0 || startedAt <= 0 ? null : Math.max(0L, at - startedAt); }

    private static String when(Long at) { return at == null ? "" : " at " + clock(at); }

    static String clock(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String endLabel(String source) {
        if (PresenceTimeline.END_VICTORY.equals(source)) return "server victory";
        if (PresenceTimeline.END_DIALOGUE.equals(source)) return "final-boss dialogue";
        if (PresenceTimeline.END_BOSS.equals(source)) return "last boss removed";
        return "end observed";
    }

    private static Outcome unknown(String reason) { return new Outcome(Kind.UNKNOWN, null, null, -1, null, false, reason); }

    private static String name(Entity player) {
        String name = player == null ? null : player.name();
        return name == null || name.isEmpty() ? null : name;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `GRADLE test --tests tomato.gui.dps.EncounterOutcomesTest`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tomato/gui/dps/EncounterOutcomes.java src/test/java/tomato/gui/dps/EncounterOutcomesTest.java
git commit -m "Decide each player's dungeon outcome from the presence timeline

One pure place turns a timeline into Completed, Died, Nexused, In
progress or Unknown with its time and reason, so the meter and the text
view cannot disagree.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Record the timeline during capture and save it with the recording

**Files:**
- Modify: `src/main/java/tomato/backend/data/TomatoData.java` (fields near line 229; `captureTerminated` 338-367; `update` 503-534; `entityUpdate` 565-578; `clear` 1207-1245; `resetEncounterGraph` 1252-1282; `text` 1806-1823; `notification` 1830-1835)
- Modify: `src/main/java/tomato/backend/TomatoPacketCapture.java:108-110`
- Modify: `src/main/java/tomato/Tomato.java` (`packetRegister`)
- Modify: `src/main/java/tomato/backend/data/DpsData.java`
- Modify: `src/main/java/tomato/backend/data/DpsSnapshot.java`
- Modify: `src/main/java/tomato/gui/dps/EncounterImport.java:38-43` (`ALLOWED`)
- Modify: `src/test/java/tomato/gui/dps/EncounterImportFilterTest.java` (`realistic`, and one added assertion in `theAllowListIsExactlyWhatRealRecordingsResolve`)
- Test: `src/test/java/tomato/backend/data/PresenceCaptureTest.java`

**Interfaces:**
- Consumes: `PresenceTimeline` (Task 2), `EncounterOutcomes` (Task 3, tests only), `CompletionDialogue.evidence` (Task 1).
- Produces: `DpsData.getPresence()` / `DpsData.setPresence(PresenceTimeline)` (public; null for old recordings); `DpsSnapshot.presence` (a copy, never null) and `DpsSnapshot.startedAt` (`long`, -1 before the first tick); `TomatoData.localDeath(DeathPacket)` and `TomatoData.localEscape()` (public).

- [ ] **Step 1: Write the failing capture test**

```java
package tomato.backend.data;

import org.junit.*;
import packets.data.*;
import packets.data.enums.NotificationEffectType;
import packets.data.enums.StatType;
import packets.incoming.*;
import packets.outgoing.EscapePacket;
import tomato.backend.TomatoPacketCapture;
import tomato.gui.dps.EncounterOutcomes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Capture fills a presence timeline per logged area and closes it with the recording. */
public class PresenceCaptureTest {
    @BeforeClass public static void initializeSwing() throws Exception { javax.swing.SwingUtilities.invokeAndWait(() -> {}); }

    private TomatoData data;
    private final List<DpsData> handedOff = new ArrayList<>();

    @Before public void setUp() {
        data = new TomatoData();
        data.visitSource(null);
        data.closedEncounters(handedOff::add);
        enter("Lost Halls");
        data.setTime(1);
    }

    @Test public void aRecordingKeepsWhoLeftDiedAndStayedUntilTheVictory() {
        data.setUserId(21, 7, "AAAAAA==");
        spawn(21, "Self", 700, 700); spawn(22, "Walker", 700, 700); spawn(23, "Quitter", 126, 700); spawn(24, "Faller", 700, 700);
        drop(22); spawn(22, "Walker", 700, 700);   // out of view and back
        drop(23);                                  // gone for good
        data.notification(death("Faller", 0x0723)); drop(24);
        data.notification(notice(NotificationEffectType.Victory));
        enter("Nexus");
        DpsData recording = data.dpsData.get(0);
        PresenceTimeline presence = recording.getPresence();
        assertNotNull(presence);
        assertEquals(Integer.valueOf(21), presence.localObjectId());
        assertEquals(PresenceTimeline.END_VICTORY, presence.end().detail);
        EncounterOutcomes outcomes = EncounterOutcomes.of(presence, recording.dungeonStartTime, false, List.of(), List.of());
        assertEquals(EncounterOutcomes.Kind.COMPLETED, outcomes.outcome(21).kind);
        assertEquals(EncounterOutcomes.Kind.COMPLETED, outcomes.outcome(22).kind);
        assertEquals(EncounterOutcomes.Kind.NEXUSED, outcomes.outcome(23).kind);
        assertEquals(Integer.valueOf(18), outcomes.outcome(23).hpPercent);
        assertEquals(EncounterOutcomes.Kind.DIED, outcomes.outcome(24).kind);
        assertEquals("4 players · 2 completed · 1 nexused · 1 died", outcomes.summary());
    }

    @Test public void yourNexusAndYourDeathArriveThroughCapture() {
        TomatoPacketCapture capture = new TomatoPacketCapture(data);
        data.setUserId(21, 7, "AAAAAA=="); spawn(21, "Self", 700, 700);
        capture.packetCapture(new EscapePacket());
        enter("Nexus");
        EncounterOutcomes.Outcome mine = outcomes(data.dpsData.get(0)).outcome(21);
        assertEquals(EncounterOutcomes.Kind.NEXUSED, mine.kind); assertTrue(mine.confirmed);

        enter("Lost Halls"); data.setTime(1);
        data.setUserId(31, 8, "AAAAAA=="); spawn(31, "Self", 700, 700);
        DeathPacket death = new DeathPacket(); death.killedBy = "Synthetic foe";
        capture.packetCapture(death);
        enter("Nexus");
        mine = outcomes(data.dpsData.get(1)).outcome(31);
        assertEquals(EncounterOutcomes.Kind.DIED, mine.kind); assertEquals("Synthetic foe", mine.killedBy);
    }

    @Test public void theFinalBossLineEndsTheDungeon() {
        enter("The Void"); data.setTime(1);
        spawn(41, "Stayer", 700, 700);
        TextPacket line = new TextPacket(); line.name = "#Void Entity";
        line.text = "You fools... You can never truly defeat me! I am in all of you! I AM all of you!";
        data.text(line);
        enter("Nexus");
        assertEquals(PresenceTimeline.END_DIALOGUE, data.dpsData.get(1).getPresence().end().detail);
    }

    @Test public void theSnapshotAndTheSaveFileCarryDetachedCopies() {
        spawn(51, "Copied", 700, 700);
        DpsSnapshot snapshot = DpsSnapshot.capture(data);
        assertTrue(snapshot.startedAt > 0);
        drop(51);
        assertTrue("The published copy is detached from capture", snapshot.presence.players().get(51).present());
        enter("Nexus");
        DpsData recording = data.dpsData.get(0), saved = recording.getSaveFile(false);
        assertNotSame(recording.getPresence(), saved.getPresence());
        assertFalse(saved.getPresence().players().get(51).present());
        assertTrue("The next area starts empty", DpsSnapshot.capture(data).presence.players().isEmpty());
    }

    @Test public void aCaptureStopClosesTheTimelineAndTheRemainderStartsFresh() {
        spawn(61, "Before", 700, 700);
        data.captureTerminated();
        assertTrue(data.dpsData.get(0).getPresence().players().containsKey(61));
        assertTrue(DpsSnapshot.capture(data).presence.players().isEmpty());
    }

    private EncounterOutcomes outcomes(DpsData recording) {
        return EncounterOutcomes.of(recording.getPresence(), recording.dungeonStartTime, false, List.of(), List.of());
    }
    private void enter(String name) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name; data.setNewRealm(map);
    }
    private void spawn(int id, String name, int hp, int maxHp) {
        UpdatePacket update = new UpdatePacket(); update.tiles = new GroundTileData[0]; update.drops = new int[0];
        ObjectData object = new ObjectData(); object.objectType = 782;
        ObjectStatusData status = new ObjectStatusData(); status.objectId = id; status.pos = new WorldPosData();
        status.stats = new StatData[]{stat(StatType.NAME_STAT, 0, name), stat(StatType.HP_STAT, hp, null), stat(StatType.MAX_HP_STAT, maxHp, null)};
        object.status = status; update.newObjects = new ObjectData[]{object};
        data.update(update);
    }
    private void drop(int id) {
        UpdatePacket update = new UpdatePacket(); update.tiles = new GroundTileData[0];
        update.newObjects = new ObjectData[0]; update.drops = new int[]{id};
        data.update(update);
    }
    private static StatData stat(StatType type, int value, String text) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; stat.stringStatValue = text;
        return stat;
    }
    private static NotificationPacket notice(NotificationEffectType effect) {
        NotificationPacket n = new NotificationPacket(); n.effect = effect; return n;
    }
    private static NotificationPacket death(String name, int grave) {
        NotificationPacket n = notice(NotificationEffectType.PlayerDeath); n.pictureType = grave;
        n.message = "{\"k\":\"s.death\",\"t\":{\"player\":\"" + name + "\",\"level\":\"20\"}}";
        return n;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `GRADLE test --tests tomato.backend.data.PresenceCaptureTest`
Expected: compilation FAILS with `cannot find symbol ... getPresence()` (and `presence`, `startedAt`, `localDeath`).

- [ ] **Step 3: Add the field to `DpsData`**

After `private Long contextCapturedAt;` (line 35) add:

```java
    // Optional (outcome tracking): who was present, died or left, and when the dungeon ended. Null in older streams.
    private PresenceTimeline presence;
```

In `getSaveFile`, after `copy.localPlayerObjectId = localPlayerObjectId; copy.contextCapturedAt = contextCapturedAt;` add:

```java
        copy.presence = presence == null ? null : presence.copy();
```

After `public LocalPlayerContext getLocalPlayerContext() { return localPlayerContext; }` add:

```java
    /** The recording's presence timeline, or null when it was recorded before outcome tracking. */
    public PresenceTimeline getPresence() { return presence; }

    /** Set once by capture while closing the encounter, and by fixtures; the timeline is owned by this recording afterwards. */
    public void setPresence(PresenceTimeline presence) { this.presence = presence; }
```

Do not change `serialVersionUID`.

- [ ] **Step 4: Record in `TomatoData`**

4a. Imports (with the others at the top): `import packets.incoming.DeathPacket;` (skip if `packets.incoming.*` is already imported), `import packets.packetcapture.logger.CompletionDialogue;`, `import tomato.gui.dps.shared.DeathParser;`, `import tomato.realmshark.ParseDungeon;`.

4b. After `protected ArrayList<NotificationPacket> deathNotifications = new ArrayList<>();` (line 229-230) add:

```java
    // Who entered and left view, died, and when the area ended (outcome tracking); reset with the death notifications.
    private PresenceTimeline presence = new PresenceTimeline();
```

4c. In `entityUpdate`, in the player branch, after `ParsePanelGUI.addPlayer(id, entity);` add:

```java
            presence.recordSeen(id, entity.name(), idType, timePc, localPlayer);
```

4d. In `update`, inside `if (e != null) { if (isPlayerEntity(e.objectType)) {`, as the first statement of the inner block add:

```java
                    presence.recordLeft(dropId, e.hp(), e.maxHp(), timePc);
```

and replace

```java
            if (entityHitList.containsKey(dropId)) {
                killedEntitys.add(e);
            }
```

with

```java
            if (entityHitList.containsKey(dropId)) {
                killedEntitys.add(e);
                // A boss leaving the hit list ends the dungeon only when no victory or final-boss line does.
                if (e != null && e.isBossMob()) presence.recordEnd(PresenceTimeline.END_BOSS, timePc);
            }
```

4e. Replace `notification`:

```java
    public void notification(NotificationPacket packet) {
        if (packet.effect == NotificationEffectType.PlayerDeath) {
            deathNotifications.add(packet);
            presence.recordDeath(DeathParser.extractName(packet), packet.pictureType, timePc);
        } else if (packet.effect == NotificationEffectType.Victory) {
            presence.recordEnd(PresenceTimeline.END_VICTORY, timePc);
        }
        KeypopGUI.packet(this, packet);
    }

    /** Your own death (the DEATH packet): it names the killer. */
    public void localDeath(DeathPacket packet) {
        presence.recordLocalDeath(packet.killedBy, timePc);
    }

    /** You pressed nexus (the outgoing ESCAPE packet). */
    public void localEscape() {
        presence.recordEscape(timePc);
    }
```

4f. In `text`, directly after the `dammahCountered` `if` block and before `lootAttribution.handleTextPacket(...)`, add:

```java
        if (map != null && CompletionDialogue.evidence(ParseDungeon.canonicalMapName(map), p) != null)
            presence.recordEnd(PresenceTimeline.END_DIALOGUE, timePc);
```

4g. In `clear`, directly after the `DpsData closed = new DpsData(...);` statement add:

```java
            closed.setPresence(presence);   // the recording owns it; resetEncounterGraph starts a new one
```

4h. In `resetEncounterGraph`, after `deathNotifications = new ArrayList<>();` add:

```java
        presence = new PresenceTimeline();
```

4i. In `captureTerminated`, directly after `DpsData closed = new DpsData(map, hits, ...);` add `closed.setPresence(presence);`, and after the `deathNotifications = new ArrayList<>();` line in that method add `presence = new PresenceTimeline();`.

4j. Add package-private accessors for the snapshot (next to `getDeathNotifications`):

```java
    /** A detached copy of the in-progress timeline (producer thread only, like DpsSnapshot). */
    PresenceTimeline presenceCopy() { return presence.copy(); }

    /** First tick of the in-progress encounter, or -1 before it. */
    long encounterStartedAt() { return timePcFirst; }
```

- [ ] **Step 5: Carry it in `DpsSnapshot`**

Add fields after `public final tomato.history.link.EncounterContext context;`:

```java
    /** The live encounter's presence timeline (a detached copy, never null). */
    public final PresenceTimeline presence;
    /** The live encounter's first tick, or -1 before it. */
    public final long startedAt;
```

and at the end of the constructor:

```java
        presence=data.presenceCopy();
        startedAt=data.encounterStartedAt();
```

- [ ] **Step 6: Route and subscribe the packets**

In `TomatoPacketCapture.packetCapture`, directly after the `NotificationPacket` branch add:

```java
        } else if (packet instanceof DeathPacket) {
            data.localDeath((DeathPacket) packet);
        } else if (packet instanceof EscapePacket) {
            data.localEscape();
```

In `Tomato.packetRegister`, directly after the `PacketType.NOTIFICATION` registration add:

```java
        Register.INSTANCE.register(PacketType.DEATH, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.ESCAPE, packCap::packetCapture);
```

- [ ] **Step 7: Run the capture test**

Run: `GRADLE test --tests tomato.backend.data.PresenceCaptureTest`
Expected: PASS (5 tests). If `spawn` does not set the name, check that `Entity.name()` reads `NAME_STAT` for type 782 before changing anything else.

- [ ] **Step 8: Allow the timeline in `.dps` imports (test first)**

In `EncounterImportFilterTest.realistic`, replace the final `return new DpsData(...);` with:

```java
        DpsData data = new DpsData(map, hits, deaths, seconds * 1000L, T, log, party.get(0), new EncounterContext(VISIT, 1, T - 5_000));
        if (!party.isEmpty()) {
            PresenceTimeline presence = new PresenceTimeline();
            for (Entity player : party) presence.recordSeen(player.id, player.name(), player.objectType, T, player.isUser());
            presence.recordLeft(party.get(party.size() - 1).id, 600, 700, T + 1_000);
            presence.recordDeath("Player1", 0x0723, T + 2_000);
            presence.recordEscape(T + 2_500);
            presence.recordLocalDeath("Synthetic foe", T + 2_600);
            presence.recordEnd(PresenceTimeline.END_VICTORY, T + 3_000);
            data.setPresence(presence);
        }
        return data;
```

Add `import tomato.backend.data.PresenceTimeline;` to that test. In `theAllowListIsExactlyWhatRealRecordingsResolve`, next to `assertEquals(VISIT, exported.data.getEncounterContext().visit);`, add (add beside):

```java
        assertEquals(Integer.valueOf(1), exported.data.getPresence().localObjectId());
        assertEquals(PresenceTimeline.END_VICTORY, exported.data.getPresence().end().detail);
```

Run: `GRADLE test --tests tomato.gui.dps.EncounterImportFilterTest`
Expected: FAIL; `Resolved by a real recording but not allowed: tomato.backend.data.PresenceTimeline`.

- [ ] **Step 9: Extend `EncounterImport.ALLOWED`**

Add to the `Set.of(...)` list (after `"tomato.backend.data.PlayerRemoved"`):

```java
        "tomato.backend.data.PresenceTimeline", "tomato.backend.data.PresenceTimeline$Player",
        "tomato.backend.data.PresenceTimeline$Change", "tomato.backend.data.PresenceTimeline$Death",
        "tomato.backend.data.PresenceTimeline$Mark",
```

Run: `GRADLE test --tests tomato.gui.dps.EncounterImportFilterTest --tests tomato.backend.data.PresenceCaptureTest --tests tomato.backend.data.EncounterIdentityTest --tests tomato.backend.data.DpsDataTest --tests tomato.CapturePublicationTest --tests tomato.gui.dps.CombatAutosaveTest`
Expected: PASS (all). The hook check (`everyPacketTypeAndWhatItHoldsIsAllowedAndNoAdmittedClassHasAHook`) passes because the timeline classes define no deserialization hooks.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/tomato/backend/data/TomatoData.java src/main/java/tomato/backend/data/DpsData.java src/main/java/tomato/backend/data/DpsSnapshot.java src/main/java/tomato/backend/TomatoPacketCapture.java src/main/java/tomato/Tomato.java src/main/java/tomato/gui/dps/EncounterImport.java src/test/java/tomato/backend/data/PresenceCaptureTest.java src/test/java/tomato/gui/dps/EncounterImportFilterTest.java
git commit -m "Record who stayed, died or left during each logged area

Capture now fills the presence timeline from updates, death and victory
notices, final-boss lines and your own DEATH and ESCAPE packets, closes
it with the recording, and publishes copies to the meter. .dps files
carry it, so the importer allows its classes.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Outcomes in the DPS Meter

**Files:**
- Modify: `src/main/java/tomato/gui/dps/MeterDpsGUI.java`
- Modify: `src/main/java/tomato/gui/dps/DpsGUI.java:594-625` (`renderData`, `present`, `DisplayFrame`)
- Test: `src/test/java/tomato/gui/dps/MeterOutcomeTest.java`

**Interfaces:**
- Consumes: `EncounterOutcomes` (Task 3), `DpsSnapshot.presence` / `startedAt` and `DpsData.getPresence()` (Task 4).
- Produces: `MeterDpsGUI.setPresence(PresenceTimeline presence, long startedAt)` (package-private); the model's column 10 "Outcome" holds `EncounterOutcomes.Outcome`; label component `outcomeLine` named `dps-outcome-summary`.

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.backend.data.Projectile;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

import static org.junit.Assert.*;

public class MeterOutcomeTest {
    private static Entity enemy(int id, Entity alice, Entity bob) {
        Entity e = new Entity(null, id, 0);
        StatData hp = new StatData(); hp.statValue = 1000; e.stat.set(StatType.MAX_HP_STAT, hp);
        e.genericDamageHit(alice, new Projectile(200), 1000); e.genericDamageHit(bob, new Projectile(800), 2000);
        e.updateDamageTaken(1000); e.updateDamageTaken(3000);
        return e;
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return (T) f.get(obj);
    }
    private static Map<String, Object> outcomesByName(JTable table) {
        Map<String, Object> byName = new HashMap<>();
        for (int i = 0; i < table.getModel().getRowCount(); i++) byName.put(String.valueOf(table.getModel().getValueAt(i, 0)), table.getModel().getValueAt(i, 10));
        return byName;
    }
    private static int viewRow(JTable table, String name) {
        for (int i = 0; i < table.getRowCount(); i++) if (name.equals(table.getValueAt(i, 0))) return i;
        throw new AssertionError(name);
    }

    @Test public void theOutcomeColumnAndSummaryFollowThePresenceTimeline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Filter.disable();
                Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
                PresenceTimeline presence = new PresenceTimeline();
                presence.recordSeen(1, "Alice", 768, 1_000, false); presence.recordSeen(2, "Bob", 775, 1_000, false);
                presence.recordLeft(2, 126, 700, 161_000);
                presence.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
                MeterDpsGUI meter = new MeterDpsGUI();
                meter.setPresence(presence, 1_000);
                meter.renderData(null, Collections.singletonList(enemy(11, alice, bob)), new ArrayList<>(), 0, false);
                JTable table = field(meter, "table"); JLabel line = field(meter, "outcomeLine");
                assertEquals("Outcome", table.getModel().getColumnName(10));
                Map<String, Object> byName = outcomesByName(table);
                assertEquals("Completed", byName.get("Alice").toString());
                assertEquals("Nexused 2:40 · 18% HP", byName.get("Bob").toString());
                assertEquals("2 players · 1 completed · 1 nexused", line.getText());
                assertEquals("dps-outcome-summary", line.getName());

                table.clearSelection();
                int row = viewRow(table, "Bob");
                Component name = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                assertEquals("A player who did not complete is muted", ContentStyle.color("muted"), name.getForeground());
                assertTrue(((JComponent) name).getToolTipText().endsWith("Nexused 2:40 · 18% HP"));
                Component outcome = table.prepareRenderer(table.getCellRenderer(row, 10), row, 10);
                assertTrue(((JComponent) outcome).getToolTipText().contains("did not return before the dungeon ended"));
                int alicesRow = viewRow(table, "Alice");
                Component completed = table.prepareRenderer(table.getCellRenderer(alicesRow, 0), alicesRow, 0);
                assertNotEquals(ContentStyle.color("muted"), completed.getForeground());
                assertEquals("DPS numbers stay for every player", 800L, table.getModel().getValueAt(table.convertRowIndexToModel(row), 2));
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void aLiveEncounterBeforeTheEndIsInProgress() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Filter.disable();
                Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
                PresenceTimeline presence = new PresenceTimeline();
                presence.recordSeen(1, "Alice", 768, 1_000, false); presence.recordSeen(2, "Bob", 775, 1_000, false);
                MeterDpsGUI meter = new MeterDpsGUI();
                meter.setPresence(presence, 1_000);
                meter.renderData(null, Collections.singletonList(enemy(11, alice, bob)), new ArrayList<>(), 0, true);
                JTable table = field(meter, "table"); JLabel line = field(meter, "outcomeLine");
                assertEquals("In progress", outcomesByName(table).get("Bob").toString());
                assertEquals("2 players · outcome pending", line.getText());
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void aRecordingWithoutATimelineShowsNameMatchedDeathsOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Filter.disable();
                Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
                ArrayList<NotificationPacket> notes = new ArrayList<>(List.of(EncounterOutcomesTest.death("Bob", 0x0723)));
                MeterDpsGUI meter = new MeterDpsGUI();
                meter.setPresence(null, -1);
                meter.renderData(null, Collections.singletonList(enemy(11, alice, bob)), notes, 0, false);
                JTable table = field(meter, "table"); JLabel line = field(meter, "outcomeLine");
                Map<String, Object> byName = outcomesByName(table);
                assertEquals("Died", byName.get("Bob").toString());
                assertEquals("Unknown", byName.get("Alice").toString());
                assertEquals("Outcomes unavailable (recorded before this feature)", line.getText());
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `GRADLE test --tests tomato.gui.dps.MeterOutcomeTest`
Expected: compilation FAILS with `cannot find symbol ... setPresence`.

- [ ] **Step 3: Add the state and summary line to `MeterDpsGUI`**

Add `import assets.ImageBuffer;` to the imports. After `private double meterMaximum;` add:

```java
    /** The shown encounter's presence timeline (null for a recording made before outcome tracking), its start and its death notices. */
    private PresenceTimeline presence;
    private long startedAt = -1;
    private ArrayList<NotificationPacket> notices = new ArrayList<>();
    private EncounterOutcomes outcomes = EncounterOutcomes.none();
    private static final String OUTCOME_HELP = "Completed: in the dungeon when it ended (server victory, final-boss line or the last boss removed). "
        + "Nexused: left view before the end and did not return (inferred, except your own nexus). Died: a death notice before the end.";
    private final JLabel outcomeLine = new JLabel(" ") {
        @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("muted")); }
    };
```

In the constructor, replace

```java
        summary.setAlignmentX(LEFT_ALIGNMENT); scope.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(summary); controls.add(scope);
```

with

```java
        ContentStyle.font(outcomeLine, ContentStyle.metadata(ContentStyle.body()));
        outcomeLine.setName("dps-outcome-summary"); outcomeLine.putClientProperty("html.disable", true);
        summary.setAlignmentX(LEFT_ALIGNMENT); scope.setAlignmentX(LEFT_ALIGNMENT); outcomeLine.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(summary); controls.add(scope); controls.add(outcomeLine);
```

After `setContext(Object key, Entity player, DpsData.LocalPlayerContext context)` add:

```java
    /** The shown encounter's presence timeline (null for a recording made before outcome tracking) and its first tick. */
    void setPresence(PresenceTimeline presence, long startedAt) { this.presence = presence; this.startedAt = startedAt; }
```

In `renderData`, directly after `targets.removeIf(Entity::isPlayerCharacter);` add:

```java
        notices = notes == null ? new ArrayList<>() : notes;
        outcomes = EncounterOutcomes.of(presence, startedAt, isLive, EncounterOutcomes.playersOf(targets), notices);
```

In `filterRows`, after the `scope.setText(...)` statement add:

```java
        outcomeLine.setText(outcomes.summary()); outcomeLine.setToolTipText(OUTCOME_HELP);
```

- [ ] **Step 4: Add the column**

In the constructor, after `for (String count : new String[]{"Hits dealt", "Hits taken"}) kinds.put(count, ColumnKind.COUNT);` add `kinds.put("Outcome", ColumnKind.TEXT);`, and after the class column's `setCellRenderer(...)` block add:

```java
        table.getColumnModel().getColumn(10).setCellRenderer(new OutcomeRenderer());
```

In `MeterModel`, replace `names` and `getColumnClass`:

```java
        private final String[] names = {"Player / meter", "Class", "Damage", "DPS", "Recorded share %", "Hits dealt", "Avg hit", "Max hit", "Taken (est.)", "Hits taken", "Outcome"};
```

```java
        public Class<?> getColumnClass(int c) { return c == 10 ? EncounterOutcomes.Outcome.class : c < 2 ? String.class : (c == 3 || c == 4 || c == 6 ? Double.class : Long.class); }
```

In `value(CombatMeterData.Row row, int c)`, add before `default:`:

```java
            case 10: return outcomes.outcome(row.player);
```

In `BarRenderer.getTableCellRendererComponent`, directly after the `setToolTipText(...)` line add:

```java
            EncounterOutcomes.Outcome outcome = outcomes.outcome(entry.player);
            if (outcome.didNotComplete()) {
                if (!selected) setForeground(ContentStyle.color("muted"));
                setToolTipText(getToolTipText() + " · " + outcome.label());
            }
```

Add the renderer class next to `BarRenderer`:

```java
    /** The Outcome column: its label, a gravestone for a death, the reason as the tooltip; color follows the text. */
    private final class OutcomeRenderer extends ContentStyle.Cell {
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            EncounterOutcomes.Outcome outcome = value instanceof EncounterOutcomes.Outcome
                ? (EncounterOutcomes.Outcome) value : EncounterOutcomes.none().outcome(-1);
            setText(outcome.label()); setToolTipText(outcome.reason);
            setFont(tableMetadata(t.getFont()));
            setIcon(outcome.graveIcon > 0 ? grave(outcome.graveIcon) : null); setIconTextGap(Tokens.XS);
            if (!selected) {
                switch (outcome.kind) {
                    case DIED: setForeground(ContentStyle.color("rose")); break;
                    case NEXUSED: setForeground(ContentStyle.color("amber")); break;
                    case COMPLETED: break;
                    default: setForeground(ContentStyle.color("muted"));
                }
            }
            washMine(visible.get(t.convertRowIndexToModel(row)), selected, this);
            return this;
        }
    }
    private static Icon grave(int type) {
        try { return ImageBuffer.getOutlinedIcon(type, 16); } catch (RuntimeException e) { return null; }
    }
```

- [ ] **Step 5: Hand the timeline over in `DpsGUI`**

Add `import tomato.backend.data.PresenceTimeline;`. In the private `renderData(...)`, replace the `displayed=new DisplayFrame(...)` line with:

```java
        PresenceTimeline presence = b ? rendered.presence : saved.getPresence();
        long startedAt = b ? rendered.startedAt : saved.dungeonStartTime;
        displayed=new DisplayFrame(map,entityHitList,notifications,totalDungeonPcTime,b,b?map:saved,b?rendered.player:null,context,link,presence,startedAt);
```

In `present`, after `displayIcon.setPlayerContext(frame.context);` add:

```java
        displayMeter.setPresence(frame.presence, frame.startedAt);
```

Replace `DisplayFrame` with:

```java
    private static final class DisplayFrame {
        final MapInfoPacket map;final Entity[] targets;final ArrayList<NotificationPacket> notes;
        final long elapsed;final boolean live;final Object key;final Entity player;final DpsData.LocalPlayerContext context;final EncounterLink link;
        final PresenceTimeline presence;final long startedAt;
        final String displayedAt=java.time.Instant.now().toString();
        DisplayFrame(MapInfoPacket map,Entity[] targets,ArrayList<NotificationPacket> notes,long elapsed,boolean live,Object key,Entity player,DpsData.LocalPlayerContext context,EncounterLink link,PresenceTimeline presence,long startedAt){this.map=map;this.targets=targets;this.notes=notes;this.elapsed=elapsed;this.live=live;this.key=key;this.player=player;this.context=context;this.link=link;this.presence=presence;this.startedAt=startedAt;}
    }
```

- [ ] **Step 6: Run the meter tests**

Run: `GRADLE test --tests tomato.gui.dps.MeterOutcomeTest --tests tomato.gui.dps.CombatMeterTest --tests tomato.gui.dps.DpsPresentationTest --tests tomato.gui.dps.DpsRefreshTest --tests tomato.gui.dps.DpsFilterBarTest --tests tomato.gui.dps.DpsInspectMenuTest`
Expected: PASS (all). If an existing test counts meter columns (10), report it and update that count as **replace** (the column was added intentionally).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/tomato/gui/dps/MeterDpsGUI.java src/main/java/tomato/gui/dps/DpsGUI.java src/test/java/tomato/gui/dps/MeterOutcomeTest.java
git commit -m "Show who completed, died or nexused on the DPS meter

A sortable Outcome column, a party summary line and muted names for
players who did not complete; every player keeps their DPS numbers.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Outcomes in the text view

**Files:**
- Modify: `src/main/java/tomato/gui/dps/DpsToString.java`
- Modify: `src/main/java/tomato/gui/dps/StringDpsGUI.java`
- Modify: `src/main/java/tomato/gui/dps/DpsGUI.java` (one line in `present`)
- Modify: `src/test/java/tomato/gui/dps/CombatReportingTest.java:31` (call site only)
- Test: `src/test/java/tomato/gui/dps/DpsTextOutcomeTest.java`

**Interfaces:**
- Consumes: `EncounterOutcomes` (Task 3).
- Produces: `DpsToString.stringDmgRealtime(MapInfoPacket, List<Entity>, ArrayList<NotificationPacket>, LocalPlayerContext, long, EncounterOutcomes)`; `DpsToString.display(Entity, EncounterOutcomes, LocalPlayerContext, EquipmentUsageAggregator)` (replaces the `Map<String, Integer> deathMap` overload); `StringDpsGUI.setPresence(PresenceTimeline, long)`.

- [ ] **Step 1: Write the failing test**

```java
package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.backend.data.Projectile;

import java.util.*;
import java.util.List;

import static org.junit.Assert.*;

public class DpsTextOutcomeTest {
    private static Entity enemy(Entity alice, Entity bob) {
        Entity e = new Entity(null, 11, 0);
        StatData hp = new StatData(); hp.statValue = 1000; e.stat.set(StatType.MAX_HP_STAT, hp);
        e.genericDamageHit(alice, new Projectile(200), 1000); e.genericDamageHit(bob, new Projectile(800), 2000);
        e.updateDamageTaken(1000); e.updateDamageTaken(3000);
        return e;
    }
    private static String lineFor(String text, String name) {
        for (String line : text.split("\n")) if (line.contains(" DMG: ") && line.contains(name)) return line;
        throw new AssertionError(name + " in\n" + text);
    }

    @Test public void thePartyBlockAndTheEnemyLinesShowDungeonOutcomes() {
        Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
        PresenceTimeline presence = new PresenceTimeline();
        presence.recordSeen(1, "Alice", 768, 1_000, false); presence.recordSeen(2, "Bob", 775, 1_000, false);
        presence.recordLeft(2, 126, 700, 161_000); presence.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        List<Entity> enemies = Collections.singletonList(enemy(alice, bob));
        EncounterOutcomes outcomes = EncounterOutcomes.of(presence, 1_000, false, EncounterOutcomes.playersOf(enemies), List.of());
        MapInfoPacket map = new MapInfoPacket(); map.name = "Synthetic Dungeon";
        String text = DpsToString.stringDmgRealtime(map, enemies, new ArrayList<>(), null, 2123, outcomes);
        assertTrue(text, text.contains("Party outcome: 2 players · 1 completed · 1 nexused"));
        assertTrue(text, text.contains("Nexused 2:40 · 18% HP"));
        assertTrue(lineFor(text, "Bob").contains("Nexused 2:40"));
        assertFalse("Completers carry no tag", lineFor(text, "Alice").contains("Nexus"));
    }

    @Test public void theFiveArgumentFormKeepsDeathsFromNotices() {
        Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
        ArrayList<NotificationPacket> notes = new ArrayList<>(List.of(EncounterOutcomesTest.death("Bob", 0x0723)));
        String text = DpsToString.stringDmgRealtime(null, Collections.singletonList(enemy(alice, bob)), notes, null, 2123);
        assertTrue(text, text.contains("Party outcome: Outcomes unavailable (recorded before this feature)"));
        assertTrue(lineFor(text, "Bob").contains("Died"));
        assertFalse("A player without a death notice is not called a nexus any more", lineFor(text, "Alice").contains("Nexus"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `GRADLE test --tests tomato.gui.dps.DpsTextOutcomeTest`
Expected: compilation FAILS (no six-argument `stringDmgRealtime`).

- [ ] **Step 3: Change `DpsToString`**

Replace `stringDmgRealtime` with the pair below (the header lines stay exactly as they are today):

```java
    /** Legacy entry: outcomes from the notices only (no presence timeline). */
    public static String stringDmgRealtime(
        MapInfoPacket map,
        List<Entity> sortedEntityHitList,
        ArrayList<NotificationPacket> notifications,
        LocalPlayerContext player,
        long totalDungeonPcTime
    ) {
        return stringDmgRealtime(map, sortedEntityHitList, notifications, player, totalDungeonPcTime,
            EncounterOutcomes.of(null, -1, false, EncounterOutcomes.playersOf(sortedEntityHitList), notifications));
    }

    /**
     * Real time string display.
     *
     * @return logged dps output as a string.
     */
    public static String stringDmgRealtime(
        MapInfoPacket map,
        List<Entity> sortedEntityHitList,
        ArrayList<NotificationPacket> notifications,
        LocalPlayerContext player,
        long totalDungeonPcTime,
        EncounterOutcomes outcomes
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Legacy: % enemy max HP = player damage / captured enemy max HP; hidden players do not change it.\n")
            .append(CombatMeterData.WINDOW_DEFINITION).append("\n\n");

        if (DpsDisplayOptions.equipmentOption == 3) {
            sb.append(
                "Icons are not visible in live tab. Use \"<\" to see icons.\n\n"
            );
        }

        if (map != null) {
            sb
                .append(map.name)
                .append(" ")
                .append(" [").append(DisplayFormat.formatDurationMillis(totalDungeonPcTime)).append("]")
                .append("\n\n");
        }

        appendPartyOutcome(sb, outcomes);

        for (Entity e : sortedEntityHitList) {
            if (!isValidEntity(e)) continue;

            EquipmentUsageAggregator eqAgg =
                (DpsDisplayOptions.equipmentOption == 0)
                    ? null
                    : EquipmentUsageAggregator.of(e);

            sb.append(display(e, outcomes, player, eqAgg)).append("\n");
        }

        return sb.toString();
    }

    /** "Party outcome: …" then one line per player (name, class, outcome); nothing when there are no players. */
    static void appendPartyOutcome(StringBuilder sb, EncounterOutcomes outcomes) {
        if (outcomes == null || outcomes.lines().isEmpty()) return;
        sb.append("Party outcome: ").append(outcomes.summary()).append('\n');
        for (EncounterOutcomes.Line line : outcomes.lines()) {
            sb.append("    ");
            DpsTextFormat.appendPaddedRight(sb, line.name, 12);
            sb.append(' ');
            String className = CharacterClass.getName(line.classType);
            DpsTextFormat.appendPaddedRight(sb, className == null ? "Unknown" : className, 10);
            sb.append(' ').append(line.outcome.label()).append('\n');
        }
        sb.append('\n');
    }
```

Change `display`'s second parameter from `Map<String, Integer> deathMap` to `EncounterOutcomes outcomes`, and replace the whole `// Death/Nexus info, if available` block (the `if (entity.playerDropped != null) { ... }` block) with:

```java
            // Dungeon-level outcome for players who did not complete (docs/DPS-METERS.md, "Party outcomes")
            EncounterOutcomes.Outcome outcome = outcomes == null ? null : outcomes.outcome(dmg.owner);
            if (outcome != null && outcome.didNotComplete()) extra += outcome.label();
```

Remove the imports that are now unused (`java.util.Map`, `tomato.backend.data.PlayerRemoved`, `tomato.gui.dps.shared.DeathParser`) only if nothing else in the file uses them.

- [ ] **Step 4: Update the text display and its callers**

In `StringDpsGUI`, add `import tomato.backend.data.PresenceTimeline;`, then after `void setPlayerContext(...)` add:

```java
    private PresenceTimeline presence;
    private long startedAt = -1;
    /** The shown encounter's presence timeline (null before outcome tracking) and its first tick. */
    void setPresence(PresenceTimeline presence, long startedAt) { this.presence = presence; this.startedAt = startedAt; }
```

and replace its `renderData` body with:

```java
        EncounterOutcomes outcomes = EncounterOutcomes.of(presence, startedAt, isLive, EncounterOutcomes.playersOf(sortedEntityHitList), notifications);
        setTextAreaAndLabelDPS(DpsToString.stringDmgRealtime(map, sortedEntityHitList, notifications, playerContext, totalDungeonPcTime, outcomes));
```

In `DpsGUI.present`, next to the `displayMeter.setPresence(...)` line from Task 5, add:

```java
        displayString.setPresence(frame.presence, frame.startedAt);
```

In `CombatReportingTest` line 31, **replace** `DpsToString.display(enemy,Collections.emptyMap(),null,null)` with `DpsToString.display(enemy,EncounterOutcomes.none(),null,null)` (the death-map parameter no longer exists; the assertions are unchanged).

- [ ] **Step 5: Run the text tests**

Run: `GRADLE test --tests tomato.gui.dps.DpsTextOutcomeTest --tests tomato.gui.dps.CombatReportingTest --tests tomato.gui.dps.DpsFormattingTest --tests tomato.gui.dps.MeterOutcomeTest`
Expected: PASS (all).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tomato/gui/dps/DpsToString.java src/main/java/tomato/gui/dps/StringDpsGUI.java src/main/java/tomato/gui/dps/DpsGUI.java src/test/java/tomato/gui/dps/DpsTextOutcomeTest.java src/test/java/tomato/gui/dps/CombatReportingTest.java
git commit -m "Use dungeon-level outcomes in the DPS text view

The old per-enemy tag called anyone who walked out of view a nexus.
The text view now lists the party's outcomes and tags only players who
died or nexused before the dungeon ended.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Documentation and final validation

**Files:**
- Modify: `docs/DPS-METERS.md` (new section after "### Reading the meter", before "## Interpretation")

- [ ] **Step 1: Add the section**

```markdown
### Party outcomes

Every player on the meter keeps their damage and DPS; the **Outcome** column and the line under the window say whether they finished the dungeon. The text view lists the same outcomes in a "Party outcome" block.

- **Completed**: in the dungeon when it ended. The end is the server's victory notice; for The Void, The Shatters and Moonlight Village also the final boss's closing line; otherwise the last boss leaving the hit list. Leaving or dying afterwards still counts as completed.
- **Died**: a death notice for the player (or your own death packet, which names the killer) arrived before the end. Shows the time and the gravestone.
- **Nexused**: the player left view before the end and never came back, with the time and the HP they had. The game sends no nexus message for other players, so this is inferred: it can also be a disconnect or someone standing out of view when the dungeon ended. Your own nexus is confirmed by the nexus key press itself.
- **In progress**: a live encounter that has not ended yet.
- **Unknown**: the end was never seen (usually because you left first), the player was first seen after the end, or capture started mid-dungeon.

Players who walk out of view and come back are unaffected, and a reconnect under a new object ID counts as the same player. Recordings saved before outcome tracking show only deaths matched by a unique name ("Outcomes unavailable"). `.dps` files carry the outcome data, so imported recordings show the same outcomes.
```

- [ ] **Step 2: Scan for placeholders and debug output**

Run: `git diff main --stat` and `git diff main -- src | grep -n -E "TODO|FIXME|System\.out|@Ignore|\.only\(" || true`
Expected: only the new files and the files listed in this plan; no matches from the grep.

- [ ] **Step 3: Full suite and JAR (once)**

Run: `GRADLE test shadowJar` (use `run_in_background`; it takes several minutes).
Expected: BUILD SUCCESSFUL. A failure in a test class with no source file is a stale output (see Global Constraints). Any other failure: rerun that class alone once, then fix it or report it with its output. Never call it a flake without a cause.

- [ ] **Step 4: Launch smoke check**

Launch the built JAR the usual way (the `run` skill, or `java -jar build/libs/<the shadow jar>` with the project JDK). Open **Runs & DPS › Live meter**, and confirm that the table shows the **Outcome** column header, that the line under the window reads "0 players · outcome pending" or the empty state without errors, and that switching to the text view shows no exception. Do not start live capture. Close the app.

- [ ] **Step 5: Commit**

```bash
git add docs/DPS-METERS.md
git commit -m "Document party outcomes on the DPS meter

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Hand off**

Use superpowers:finishing-a-development-branch. Report the full-suite counts and the smoke result; do not push or open a PR unless the user asks.
