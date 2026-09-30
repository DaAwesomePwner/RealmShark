package ui;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import packets.PacketType;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.data.QuestData;
import packets.incoming.MapInfoPacket;
import packets.incoming.RealmScoreUpdatePacket;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import assets.IdToAsset;
import tomato.ability.AbilityObservation;
import tomato.ability.AbilityObservationStore;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.DamageSource;
import tomato.backend.data.DpsData;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.Projectile;
import tomato.backend.data.TomatoData;
import tomato.bridge.BridgeConfig;
import tomato.bridge.BridgePayload;
import tomato.bridge.BridgeService;
import tomato.gui.dps.CombatAutosave;
import tomato.gui.dps.CombatSummaries;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.EncounterImport;
import tomato.gui.glance.character.CharacterFixtures;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.gui.quest.QuestFixtures;
import tomato.gui.quest.QuestPlanning;
import tomato.gui.runs.RunFixtures;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.stats.DropContext;
import tomato.gui.stats.LootCapture;
import tomato.gui.stats.LootDashboard;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatFixtures;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
import tomato.realmshark.ParseEnchants;
import static tomato.gui.quest.QuestFixtures.*;

/**
 * One synthetic history and one synthetic app run for the final screenshot set ({@link FinalScreensEvidenceTest}), built from the
 * public test fixtures only (R4 §2.2). Synthetic names only; nothing is read from or written to the user's history or settings.
 * Times are wall-clock times in the test's zone ({@link EvidenceWorkspace} sets a fixed offset at which it is mid-afternoon), so
 * the saved records fall on Today and Yesterday whenever the test runs.
 * <p>Saved history:
 * - Today (a closed session, 11:40–14:20, one capture interval): Lost Halls a4 (left, party 5), Ice Citadel a3 (completed, party
 *   4), Lost Halls a2 and a1 (completed, parties 4 and 6), each with inspected players, a1 with resource and buff samples; eight
 *   bags (a UT with 2 enchant slots, an ST, a 3-slot Tier sword, stat potions, a boosted white bag, a bag without a recorded area
 *   and a legacy bag without a bag name); four saved combat summaries (one without a run link); fame tagged with the runs; timeline
 *   events; guild, party, whisper, world and system chat; six key pops.
 * - Yesterday (19:30–23:10): Ice Citadel y1 (completed, full detail kept) and Snake Pit y2 (no verified local row, full detail
 *   pruned), a white bag, chat and key pops.
 * - Two older sessions of account-keyed fame ({@link CharacterFixtures#fameHistory}).
 * <p>This app run (the store's own session and the live views): a Pirate Cave run without an entry time; a live fight (six
 *   players and a boss) with one closed recording and an imported copy of it; the characters journal with the Wizard in game, dead
 *   characters and Pet Yard pets; quests from the Daily Quest Room and saved plans and goals; live chat, key pops, bags, a Party
 *   roster, Ability Use evidence and Logging's discovery samples.
 */
public final class FinalScreensFixture {
    public static final String TODAY = HomeHistoryFixture.id("p6b-final-today"), YESTERDAY = HomeHistoryFixture.id("p6b-final-yesterday");
    public static final VisitRef A1 = new VisitRef(TODAY, "a1"), A2 = new VisitRef(TODAY, "a2"), A3 = new VisitRef(TODAY, "a3"),
        A4 = new VisitRef(TODAY, "a4"), Y1 = new VisitRef(YESTERDAY, "y1"), Y2 = new VisitRef(YESTERDAY, "y2");
    /** The Wizard in game and a dead character (the sheet's Death annotation). */
    public static final String WIZARD = CharacterFixtures.KEY, DEAD = CharacterFixtures.ACCOUNT + ":107";
    /** Synthetic players by object ID 1…6; the capture's own character is Bravo (object ID 2). */
    static final String[] PLAYERS = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"};
    static final int[] CLASSES = {797, 782, 784, 768, 775, 798};
    private static final int LOCAL = 1, BOSS = 6100;
    private static final long MINUTE = 60_000L;
    private static final Map<Integer, String> ENEMIES = Map.of(6000, "Synthetic Crawler", 6001, "Synthetic Spitter", 6002, "Synthetic Warden",
        BOSS, "Synthetic Colossus");
    private static final int SPIRIT_SHARD = 20;

    private FinalScreensFixture() {}

    // ---- names ----

    /** Every synthetic asset name the fixture shows: loot items (stat potions by their game IDs), quest items and enemies. */
    public static Map<Integer, String> assets() {
        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(9101, "Synthetic Voidblade"); names.put(9102, "Synthetic Aegis Robe"); names.put(9103, "Synthetic Tier Sword");
        names.put(9201, "Synthetic Frost Staff"); names.put(9301, "Synthetic Tidal Dagger"); names.put(9401, "Synthetic Viper Bow");
        names.put(9501, "Synthetic Crystal Mail"); names.put(9601, "Synthetic Plain Ring"); names.put(9701, "Synthetic Old Relic");
        names.put(2793, "Life potion"); names.put(9070, "Greater life potion"); names.put(2794, "Mana potion"); names.put(2592, "Defense potion");
        names.put(2591, "Attack potion"); names.put(2613, "Wisdom potion"); names.put(12_345, "Synthetic tonic");
        names.putAll(QuestFixtures.NAMES);
        names.put(SPIRIT_SHARD, "Spirit Shard");
        names.putAll(ENEMIES);
        return names;
    }

    /** The asset label a synthetic ID carries (potions, equipment as saved, the boss). */
    public static String label(int id) {
        if (id == BOSS) return "BOSS";
        if (id == 2793 || id == 9070 || id == 2794 || id == 2592 || id == 2591 || id == 2613 || id == 12_345) return "STATPOTION";
        if (id == 9101 || id == 9301 || id == 9701) return "EQUIPMENT,WEAPON,UT";
        if (id == 9102 || id == 9501) return "EQUIPMENT,ARMOR,ST";
        if (id == 9601) return "EQUIPMENT,RING,T4";
        if (id >= 9000 && id < 10_000) return "EQUIPMENT,WEAPON,T13";
        return "";
    }

    /** Local wall-clock time on today + {@code dayOffset} in the test's zone. */
    public static long at(int dayOffset, int hour, int minute) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).plusDays(dayOffset).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli();
    }

    // ---- saved history ----

    /** Writes Today's, Yesterday's and the two older fame sessions under the history folder {@code root}. */
    public static void writeHistory(Path root, long now) throws Exception {
        RunFixtures.session(root, TODAY, at(0, 11, 40), at(0, 14, 20), new SessionStore.Interval(at(0, 11, 40), at(0, 14, 20), "Capture stopped"));
        ActivityJournal.Visit a4 = left(HomeHistoryFixture.visit("a4", "Lost Halls", at(0, 11, 45), at(0, 11, 52), false), 5);
        ActivityJournal.Visit a3 = left(HomeHistoryFixture.visit("a3", "Ice Citadel", at(0, 12, 0), at(0, 12, 28), true), 4);
        ActivityJournal.Visit a2 = left(HomeHistoryFixture.visit("a2", "Lost Halls", at(0, 12, 40), at(0, 13, 2), true), 4);
        ActivityJournal.Visit a1 = left(HomeHistoryFixture.visit("a1", "Lost Halls", at(0, 13, 20), at(0, 13, 46), true), 6);
        inspected(a4, 5); inspected(a3, 4); inspected(a2, 4); inspected(a1, 6);
        resources(a1);
        HomeHistoryFixture.runs(root, TODAY, a4, a3, a2, a1);
        HomeHistoryFixture.loot(root, TODAY, todayDrops(true).toArray());
        save(root, TODAY, recording("Ice Citadel", A3, at(0, 12, 0), 150, 4, true), false, false);
        save(root, TODAY, recording("Lost Halls", A2, at(0, 12, 40), 180, 4, true), false, false);
        save(root, TODAY, recording("Lost Halls", A1, at(0, 13, 20), 240, 6, true), false, false);
        save(root, TODAY, recording("Sprite World", null, at(0, 14, 0), 90, 4, true), false, false);
        HomeHistoryFixture.fame(root, TODAY, fame(A4, 14_570, at(0, 11, 46)), fame(A3, 14_650, at(0, 12, 27)), fame(A2, 14_760, at(0, 13, 1)),
            fame(A1, 15_020, at(0, 13, 45)));
        lines(root.resolve(TODAY).resolve("timeline.jsonl"), timeline(a4, a3, a2, a1).toArray());
        lines(root.resolve(TODAY).resolve("chat.jsonl"), chat(at(0, 11, 42), 0).toArray());
        lines(root.resolve(TODAY).resolve("keypops.jsonl"), keyPops(at(0, 11, 41), 0).toArray());

        RunFixtures.session(root, YESTERDAY, at(-1, 19, 30), at(-1, 23, 10), new SessionStore.Interval(at(-1, 19, 30), at(-1, 23, 10), "Capture stopped"));
        ActivityJournal.Visit y1 = left(HomeHistoryFixture.visit("y1", "Ice Citadel", at(-1, 20, 0), at(-1, 20, 30), true), 5);
        ActivityJournal.Visit y2 = left(HomeHistoryFixture.visit("y2", "Snake Pit", at(-1, 21, 0), at(-1, 21, 14), true), 3);
        inspected(y1, 5); inspected(y2, 3);
        HomeHistoryFixture.runs(root, YESTERDAY, y1, y2);
        HomeHistoryFixture.loot(root, YESTERDAY, drop("White", "Ice Citadel", at(-1, 20, 15), Y1, equipment(9701, "EQUIPMENT,WEAPON,UT", 0)));
        save(root, YESTERDAY, recording("Ice Citadel", Y1, at(-1, 20, 0), 200, 5, true), true, true);
        save(root, YESTERDAY, recording("Snake Pit", Y2, at(-1, 21, 0), 80, 3, false), true, false);
        lines(root.resolve(YESTERDAY).resolve("timeline.jsonl"), timeline(y1, y2).toArray());
        lines(root.resolve(YESTERDAY).resolve("chat.jsonl"), chat(at(-1, 19, 35), 1).toArray());
        lines(root.resolve(YESTERDAY).resolve("keypops.jsonl"), keyPops(at(-1, 19, 32), 1).toArray());

        CharacterFixtures.fameHistory(root, now);
    }

    /** As the journal closes a visit on leaving its area, with the observed party size. */
    private static ActivityJournal.Visit left(ActivityJournal.Visit visit, int party) {
        visit.endReason = "Area left; completion unknown";
        visit.status = visit.completionEvidence.isEmpty() ? visit.endReason : "Completed";
        visit.rosterSize = party;
        return visit;
    }

    /** The first {@code count} synthetic players' last recorded loadouts on {@code visit}. */
    private static void inspected(ActivityJournal.Visit visit, int count) {
        for (int p = 0; p < count; p++) {
            InspectSnapshot snapshot = new InspectSnapshot(partyMember(p + 1), visit.started + (p + 1) * 20_000L);
            visit.inspectedPlayers.put(snapshot.key(), snapshot);
            visit.playerDamage.put(snapshot.key(), 90_000L - p * 11_000L);
        }
        visit.inspectedPlayerCount = count;
        visit.damageTracked = true; visit.totalDamage = visit.playerDamage.values().stream().mapToLong(Long::longValue).sum();
        visit.firstDamageAt = visit.started + 30_000L; visit.lastDamageAt = visit.lastSeen - 30_000L;
    }

    /** HP/MP samples every 20 s and two buff slices over the visit (Resources &amp; buffs, saved). */
    private static void resources(ActivityJournal.Visit visit) {
        for (long t = visit.started; t <= visit.lastSeen; t += 20_000L) {
            ActivityJournal.ResourcePoint point = new ActivityJournal.ResourcePoint(); point.time = t;
            int phase = (int) ((t - visit.started) / 20_000L);
            point.hp = 600 + (phase * 37) % 170; point.mp = 150 + (phase * 23) % 120;
            visit.resourceTimeline.add(point);
        }
        ActivityJournal.ConditionSlice first = new ActivityJournal.ConditionSlice();
        first.start = visit.started + 60_000L; first.end = visit.started + 8 * MINUTE; first.primary = 1 << 3; first.secondary = 0;
        ActivityJournal.ConditionSlice second = new ActivityJournal.ConditionSlice();
        second.start = visit.started + 12 * MINUTE; second.end = visit.started + 20 * MINUTE; second.primary = 0; second.secondary = 1;
        visit.conditionTimeline.add(first); visit.conditionTimeline.add(second);
        visit.conditionObservedMillis = 20 * MINUTE; visit.extraConditionObservedMillis = 20 * MINUTE;
        visit.conditions.put("Damaging", 7 * MINUTE); visit.extraConditions.put("Speedy", 8 * MINUTE);
        visit.hpSamples = visit.resourceTimeline.size(); visit.mpSamples = visit.resourceTimeline.size();
    }

    /** Timeline events per visit: entered, the party roster, an equipment change and (on the last visit) a capture issue. */
    private static List<Object> timeline(ActivityJournal.Visit... visits) {
        List<Object> entries = new ArrayList<>();
        for (int v = 0; v < visits.length; v++) {
            ActivityJournal.Visit visit = visits[v];
            String[][] kinds = {{"Area entered", visit.map}, {"Party roster", "Observed roster; membership identity unverified"},
                {"Equipment changed", "Weapon slot changed"}, {"Capture issue", "Unknown packet"}};
            for (int e = 0; e < (v == visits.length - 1 ? 4 : 3); e++) {
                ActivityJournal.Entry entry = new ActivityJournal.Entry();
                entry.id = visit.id + "-event-" + e; entry.visitId = visit.id; entry.map = visit.map;
                entry.time = visit.started + e * 3 * MINUTE; entry.kind = kinds[e][0]; entry.detail = kinds[e][1];
                entry.values = new LinkedHashMap<>();
                if (e == 1) { entry.values.put("partyId", 4321); entry.values.put("memberCount", visit.rosterSize); }
                if (e == 2) { entry.values.put("slot", 0); entry.values.put("hp", 700); entry.values.put("mp", 150); }
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * A fame reading of the account's Archer (#105, fame 15,020 in the journal), tagged with {@code visit}: today's runs took it from
     * 14,570 to 15,020. The Wizard in game keeps CharacterFixtures' own readings (1,000 to 1,234), so no chart mixes the two.
     */
    private static AppHistory.FameSample fame(VisitRef visit, long fame, long time) {
        return new AppHistory.FameSample(105, CharacterFixtures.ACCOUNT, fame, time, "Archer", visit, "Lost Halls");
    }

    /**
     * Today's bags (synthetic items; {@code legacy} adds the bag without a saved bag name, which the live feed never has):
     * - 13:30 White, Lost Halls (a1): the UT Voidblade (2 slots) and a Life potion;
     * - 13:38 Orange, Lost Halls (a1): the ST Aegis Robe and the Tier Sword (3 slots);
     * - 12:20 Cyan, Ice Citadel (a3): the Frost Staff (2 slots), a Defense and a greater Life potion;
     * - 13:10 boosted white, Pirate Cave (no run): the UT Tidal Dagger (1 slot);
     * - 13:55 Purple, Snake Pit (no run): the Viper Bow (no enchant data) and a Mana potion;
     * - 13:58 Brown, no recorded area: a tonic;
     * - 12:55 Blue, Lost Halls (a2): an Attack and a Wisdom potion;
     * - 11:50 (legacy) no bag name, Lost Halls (a4): a plain ring.
     */
    static List<Object> todayDrops(boolean legacy) throws Exception {
        List<Object> drops = new ArrayList<>(List.of(
            drop("White", "Lost Halls", at(0, 13, 30), A1, equipment(9101, "EQUIPMENT,WEAPON,UT", 2), potion(2793)),
            drop("Orange", "Lost Halls", at(0, 13, 38), A1, equipment(9102, "EQUIPMENT,ARMOR,ST", 0), equipment(9103, "EQUIPMENT,WEAPON,T13", 3)),
            drop("Cyan", "Ice Citadel", at(0, 12, 20), A3, equipment(9201, "EQUIPMENT,WEAPON,T13", 2), potion(2592), potion(9070)),
            drop("B.White", "Pirate Cave", at(0, 13, 10), null, equipment(9301, "EQUIPMENT,WEAPON,UT", 1)),
            drop("Purple", "Snake Pit", at(0, 13, 55), null, equipment(9401, "EQUIPMENT,WEAPON,T13", null), potion(2794)),
            drop("Brown", "Unknown", at(0, 13, 58), null, potion(12_345)),
            drop("Blue", "Lost Halls", at(0, 12, 55), A2, potion(2591), potion(2613))));
        if (legacy) drops.add(drop(null, "Lost Halls", at(0, 11, 50), A4, equipment(9601, "EQUIPMENT,RING,T4", 0)));
        return drops;
    }

    /** This app run's bag: ten minutes ago, Ice Citadel, the ST Crystal Mail (1 slot); no run recorded. */
    static Object currentDrop(long now) throws Exception {
        return drop("Orange", "Ice Citadel", now - 10 * MINUTE, null, equipment(9501, "EQUIPMENT,ARMOR,ST", 1));
    }

    /** A saved item as capture builds it (the package-private {@code LootDashboard.Item}): {@code slots} null = no enchant data. */
    private static Object equipment(int id, String labels, Integer slots) throws Exception {
        Constructor<?> summary = ParseEnchants.Summary.class.getDeclaredConstructor(int.class, int.class);
        summary.setAccessible(true);
        ParseEnchants.Summary enchants = slots == null ? ParseEnchants.summarize(null) : (ParseEnchants.Summary) summary.newInstance(slots, 0);
        Constructor<?> item = Class.forName("tomato.gui.stats.LootDashboard$Item").getDeclaredConstructor(int.class, String.class, String.class, ParseEnchants.Summary.class);
        item.setAccessible(true);
        return item.newInstance(id, assets().get(id), labels, enchants);
    }

    /** A potion as capture builds it. */
    private static Object potion(int id) throws Exception {
        Constructor<?> item = Class.forName("tomato.gui.stats.LootDashboard$Item").getDeclaredConstructor(int.class, String.class, boolean.class);
        item.setAccessible(true);
        return item.newInstance(id, assets().get(id), true);
    }

    /** A bag as capture saves it (the package-private {@code LootDashboard.Drop}); {@code visit} its exact run, or null. */
    private static Object drop(String bag, String dungeon, long time, VisitRef visit, Object... items) throws Exception {
        Constructor<?> drop = Class.forName("tomato.gui.stats.LootDashboard$Drop").getDeclaredConstructor(String.class, String.class, String.class,
            long.class, List.class, String.class, DropContext.class);
        drop.setAccessible(true);
        return drop.newInstance(bag, dungeon, "Synthetic boss", time, Arrays.asList(items), visit == null ? "" : visit.visitId,
            visit == null ? null : DropContext.capture(null, time, visit));
    }

    /** Chat across channels from {@code start}, a minute apart (the package-private {@code ChatMessage}); {@code variant} varies the text. */
    static List<Object> chat(long start, int variant) throws Exception {
        String[][] rows = {
            {"GUILD", "Aster", "", "Aster", "Lost Halls in five, bring a priest", ""},
            {"PARTY", "Wren", "", "Wren", "Ready at the portal", ""},
            {"PM", "Nova", "Bravo", "Nova", "Trading a spare potion?", "From"},
            {"PM", "Bravo", "Nova", "Nova", "Sure, meet in the Nexus", "To"},
            {"WORLD", "Kai", "", "Kai", "Anyone for the Shatters?", ""},
            {"SYSTEM", "", "", "", "Synthetic server notice: a realm is closing", ""},
            {"GUILD", "Echo", "", "Echo", variant == 0 ? "Nice drop earlier" : "Good runs tonight", ""}};
        List<Object> messages = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) messages.add(chatMessage(start + i * MINUTE, rows[i]));
        return messages;
    }

    private static Object chatMessage(long time, String[] row) throws Exception {
        Class<?> type = Class.forName("tomato.gui.chat.ChatMessage");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object channel = Enum.valueOf((Class) Class.forName("tomato.gui.chat.ChatMessage$Channel"), row[0]);
        Constructor<?> constructor = type.getDeclaredConstructor(LocalDateTime.class, channel.getClass(), String.class, String.class, String.class,
            String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()), channel, row[1], row[2], row[3], row[4], row[5]);
    }

    /** Six key pops from {@code start}, four minutes apart (the package-private {@code KeyPopEvent}). */
    static List<Object> keyPops(long start, int variant) throws Exception {
        String[][] pops = {{"Aster", "Lost Halls", "KEY"}, {"Wren", "The Shatters", "KEY"}, {"Nova", "Vial", "VIAL"},
            {"Aster", "Shield Rune", "RUNE"}, {"Kai", "Inc", "INC"}, {"Echo", variant == 0 ? "Ice Citadel" : "Snake Pit", "KEY"}};
        List<Object> events = new ArrayList<>();
        for (int i = 0; i < pops.length; i++) events.add(keyPop(start + i * 4 * MINUTE, pops[i][0], pops[i][1], pops[i][2]));
        return events;
    }

    private static Object keyPop(long time, String player, String item, String kind) throws Exception {
        Class<?> type = Class.forName("tomato.gui.keypop.KeyPopEvent");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object value = Enum.valueOf((Class) Class.forName("tomato.gui.keypop.KeyPopEvent$Kind"), kind);
        Constructor<?> constructor = type.getDeclaredConstructor(Instant.class, String.class, String.class, value.getClass());
        constructor.setAccessible(true);
        return constructor.newInstance(Instant.ofEpochMilli(time), player, item, value);
    }

    /** Saves a recording's summary where the combat autosave would; its full-detail file only when {@code fileThere}. */
    private static void save(Path root, String session, DpsData recording, boolean keptFull, boolean fileThere) throws IOException {
        CombatSummaries.Result summary = CombatSummaries.build(recording);
        summary.record().fullDetail = keptFull;
        CombatFixtures.writeRecord(root, session, summary.record());
        CombatFixtures.writeDetail(root, session, summary.detail());
        if (fileThere) writeDps(CombatAutosave.fullDetailFile(root.resolve(session), recording.getRecordingId()), recording.getSaveFile(false));
    }

    static Path writeDps(Path file, DpsData recording) throws IOException {
        Files.createDirectories(file.getParent());
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(recording); }
        return file;
    }

    /**
     * A recording of plain objects: {@code players} players Alpha… (Bravo the verified local row unless {@code verified} is false)
     * hitting six enemies of three types once a second for {@code seconds} s; the first tick 5 s after {@code entered}.
     */
    static DpsData recording(String map, VisitRef visit, long entered, int seconds, int players, boolean verified) {
        MapInfoPacket info = new MapInfoPacket(); info.name = info.displayName = map;
        long start = entered + 5_000;
        List<Entity> party = new ArrayList<>();
        for (int p = 0; p < players; p++) {
            Entity player = new Entity(null, p + 1, start); player.objectType = CLASSES[p]; player.markPlayerIdentity();
            StatData name = new StatData(); name.stringStatValue = PLAYERS[p]; player.stat.set(StatType.NAME_STAT, name);
            if (p == LOCAL) player.setUser(7);
            party.add(player);
        }
        HashMap<Integer, Entity> hits = new HashMap<>();
        List<Entity> enemies = new ArrayList<>();
        for (int e = 0; e < 6; e++) {
            Entity enemy = new Entity(null, 1000 + e, start); enemy.objectType = 6000 + e % 3; named(enemy);
            StatData hp = new StatData(); hp.statValue = 2_000 * (e % 3 + 1); enemy.stat.set(StatType.MAX_HP_STAT, hp);
            hits.put(enemy.id, enemy); enemies.add(enemy);
        }
        for (int s = 0; s < seconds; s++) for (int p = 0; p < party.size(); p++) {
            Entity enemy = enemies.get((s + p) % enemies.size());
            long time = start + s * 1000L + p * 40L;
            Projectile shot = new Projectile(180 + 45 * (players - p) + s % 7 * 10);
            shot.setSource(s % 3 == 0 ? DamageSource.ABILITY : DamageSource.WEAPON, 2101 + p);
            enemy.genericDamageHit(party.get(p), shot, time);
            enemy.updateDamageTaken(time);
        }
        return new DpsData(info, hits, new ArrayList<>(), seconds * 1000L, start, null, party.get(LOCAL),
            new EncounterContext(visit, verified ? LOCAL + 1 : null, entered));
    }

    /** As capture names an object when it first sees it: its asset name. */
    private static Entity named(Entity entity) {
        try { Field field = Entity.class.getDeclaredField("name"); field.setAccessible(true); field.set(entity, IdToAsset.objectName(entity.objectType)); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        return entity;
    }

    /** A synthetic party member (object ID {@code id}): a name, class, level 20, fame, four equipped items and base stats. */
    static Entity partyMember(int id) {
        Entity entity = new Entity(null, id, 0); entity.objectType = CLASSES[(id - 1) % CLASSES.length]; entity.markPlayerIdentity();
        entity.baseStats = new int[]{700, 252, 50, 25, 50, 75, 40, 60};
        StatData name = new StatData(); name.stringStatValue = PLAYERS[(id - 1) % PLAYERS.length]; entity.stat.set(StatType.NAME_STAT, name);
        put(entity, StatType.LEVEL_STAT, 20); put(entity, StatType.FAME_STAT, 1_000 + 250 * id); put(entity, StatType.SKIN_ID, 0);
        int[] gear = {9103, 9101, 9102, 9601};
        for (int i = 0; i < 4; i++) put(entity, StatType.valueOf("INVENTORY_" + i + "_STAT"), i == 3 && id % 2 == 0 ? -1 : gear[i]);
        return entity;
    }

    private static void put(Entity entity, StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; entity.stat.set(type, stat);
    }

    private static void lines(Path file, Object... values) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Object value : values) lines.add(SessionStore.JSON.toJson(value));
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---- this app run ----

    /**
     * The app's data before the workspace is built: the characters journal with the Wizard in game, this app run's closed recording
     * (Mad Lab, unlinked) and a live fight in progress (Alpha…Foxtrot against twelve minions and the boss "Synthetic Colossus").
     */
    public static TomatoData data(CharacterJournal journal, long now) {
        TomatoData made = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
        made.liveCharacter.publish(CharacterFixtures.live(now));
        made.dpsData.add(recording("Mad Lab", null, now - 25 * MINUTE, 60, 3, true));
        installFight(made, now - 185_000L, 180);
        return made;
    }

    /** This app run's saved session: a Pirate Cave run without an entry time and one bag. */
    public static void currentSession(SessionStore store, long now) throws Exception {
        ActivityJournal.Visit unknown = HomeHistoryFixture.visit("c1", "Pirate Cave", 0, 0, false);
        store.put("runs", unknown.id, unknown);
        store.append("loot", currentDrop(now));
        store.flush();
    }

    /** The live fight (RunsDpsEvidenceTest's): Bravo is the resolved local character; the boss for the last 40 %. */
    private static void installFight(TomatoData data, long start, int seconds) {
        CombatFixtures.Fight fight = CombatFixtures.fight("Lost Halls");
        double[] strength = {1.25, 1.0, 0.35, 0.8, 0.7, 0.55};
        List<Entity> party = new ArrayList<>();
        for (int p = 0; p < PLAYERS.length; p++) party.add(p == LOCAL ? fight.user(p + 1, CLASSES[p], PLAYERS[p]) : fight.player(p + 1, CLASSES[p], PLAYERS[p]));
        List<Entity> minions = new ArrayList<>();
        for (int e = 0; e < 12; e++) minions.add(named(fight.enemy(1000 + e, 6000 + e % 3, ENEMIES.get(6000 + e % 3), e % 3 == 1 ? null : 2_000 * (e % 3 + 1), false)));
        Entity boss = named(fight.enemy(2000, BOSS, ENEMIES.get(BOSS), 400_000, true));
        int bossFrom = seconds * 3 / 5;
        for (int s = 0; s < seconds; s++) for (int p = 0; p < party.size(); p++) {
            double wave = 0.65 + 0.35 * Math.sin(2 * Math.PI * s / 45.0 + p);
            int damage = (int) Math.max(1, Math.round(320 * strength[p] * wave * (s >= bossFrom ? 1.3 : 1)));
            fight.hit(s >= bossFrom ? boss : minions.get((s + p) % minions.size()), party.get(p), damage, start + s * 1000L + p * 40L,
                s % 3 == 0 ? DamageSource.ABILITY : DamageSource.WEAPON, 2101 + p);
        }
        for (int s = 0; s < seconds; s += 4) for (Entity player : party) fight.taken(player, boss, 30 + s % 3 * 10, start + s * 1000L + 300);
        CombatFixtures.installLive(data, fight.ticks(start, seconds * 1000L).build());
        data.player = party.get(LOCAL);
    }

    /**
     * The live views after the workspace is built, off the EDT as capture feeds them: the meter's snapshot and an imported copy of
     * this app run's recording ({@code synthetic-copy.dps}); the loot feed; chat; key pops; the Party roster; Ability Use evidence;
     * Logging's discovery samples (an area, a dungeon with inspected players, a realm score, an unknown packet and a decode failure);
     * the Pet Yard and the Daily Quest Room's quests for the account in game.
     */
    public static void live(TomatoData data, java.awt.Container shell, Path imports, long now) throws Exception {
        DpsGUI.updateMapPacket(data);
        Path copy = writeDps(imports.resolve("synthetic-copy.dps"), data.dpsData.get(0).getSaveFile(false));
        EncounterImport imported = EncounterImport.read(copy);
        javax.swing.SwingUtilities.invokeAndWait(() -> VisualEvidence.find(shell, DpsGUI.class, d -> true).encounters().add(imported));

        Method accept = LootDashboard.Feed.class.getDeclaredMethod("accept", Class.forName("tomato.gui.stats.LootDashboard$Drop"));
        accept.setAccessible(true);
        List<Object> liveDrops = todayDrops(false); liveDrops.add(currentDrop(now));
        for (Object drop : liveDrops) accept.invoke(LootCapture.get().feed(), drop);

        // Chat: as the live explorer accepts a received message (no alert, no sound).
        Field instance = tomato.gui.chat.ChatGUI.class.getDeclaredField("instance"); instance.setAccessible(true);
        Object chat = instance.get(null);
        Field explorerField = chat.getClass().getDeclaredField("explorer"); explorerField.setAccessible(true);
        Object explorer = explorerField.get(chat);
        Method deliver = explorer.getClass().getDeclaredMethod("accept", Class.forName("tomato.gui.chat.ChatMessage"));
        deliver.setAccessible(true);
        List<Object> messages = chat(now - 9 * MINUTE, 0);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try { for (Object message : messages) deliver.invoke(explorer, message); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });

        // Key pops: as capture records a pop (saved to this session and kept in the live buffer; no sound, no log file).
        Method record = tomato.gui.keypop.KeypopGUI.class.getDeclaredMethod("record", Class.forName("tomato.gui.keypop.KeyPopEvent"));
        record.setAccessible(true);
        for (Object pop : keyPops(now - 26 * MINUTE, 0)) record.invoke(null, pop);

        for (int id = 1; id <= PLAYERS.length; id++) ParsePanelGUI.addPlayer(id, partyMember(id));
        ParsePanelGUI.update();

        AbilityObservationStore abilities = AbilityObservationStore.application();
        abilities.reset();
        String session = AppHistory.store() == null ? "" : AppHistory.store().currentId();
        String[][] uses = {{"Alpha", "Archer", "Synthetic quiver", "mana drop"}, {"Bravo", "Wizard", "Synthetic spell", "mana drop"},
            {"Delta", "Rogue", "Synthetic cloak", "effect"}};
        for (int i = 0; i < uses.length; i++)
            abilities.add(new AbilityObservation(session, null, now - (5 - i) * MINUTE, i + 1, uses[i][0], uses[i][1], 3000 + i, uses[i][2],
                uses[i][3], 120, 60, "Synthetic evidence: MP fell by the ability's cost"));

        DiscoveryLog log = DiscoveryLog.INSTANCE;
        log.setSaving(false); log.setSampleMillis(0); log.setEnabled(true);
        log.observe(PacketType.MAPINFO.getIndex(), 120, map("Nexus"), "decoded", 0);
        log.observe(PacketType.MAPINFO.getIndex(), 140, map("Lost Halls"), "decoded", 0);
        for (int id = 1; id <= 4; id++) log.inspectPlayer(partyMember(id));
        RealmScoreUpdatePacket score = new RealmScoreUpdatePacket(); score.score = 2500;
        log.observe(PacketType.REALM_SCORE_UPDATE.getIndex(), 9, score, "decoded", 0);
        log.observe(255, 9, null, "unknown-id", 0);
        log.observe(255, 9, null, "decode-error", 0);

        ProgressionData progression = data.progression();
        CharacterFixtures.enterPetYard(progression, now);
        progression.quests(progression.scope(), quests(), now - 14 * MINUTE);
    }

    private static MapInfoPacket map(String name) { MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name; return map; }

    /** The Daily Quest Room's quests (QuestsEvidenceTest's board, synthetic names). */
    public static QuestData[] quests() {
        QuestData royal = QuestFixtures.data("Royal tribute", 5, repeat(FORGOTTEN_KING, 10), ROYAL_EPIC_CHEST);
        QuestData cultist = QuestFixtures.data("Cultist tribute", 5, new int[] {MALUS, MALUS}, CULTISH_EPIC_CHEST);
        QuestData festival = QuestFixtures.data("Festival exchange", 8, new int[] {FESTIVAL_TOKEN, FESTIVAL_TOKEN, FESTIVAL_TOKEN},
            MIGHTY_CHEST, ROYAL_EPIC_CHEST, CULTISH_EPIC_CHEST, STANDARD_CHEST, BEGINNER_CHEST);
        festival.itemOfChoice = true; festival.repeatable = true;
        QuestData haul = QuestFixtures.data("Mighty haul", 9, new int[] {FORGOTTEN_KING, MALUS}, MIGHTY_CHEST);
        haul.repeatable = true; haul.completed = true;
        QuestData delivery = QuestFixtures.data("Standard delivery", 5, new int[] {FORGOTTEN_KING, MALUS, MALUS, FESTIVAL_TOKEN, FESTIVAL_TOKEN, FESTIVAL_TOKEN,
            UNKNOWN_ITEM, SPIRIT_SHARD}, STANDARD_CHEST, FESTIVAL_TOKEN, FESTIVAL_TOKEN);
        QuestData errand = QuestFixtures.data("Beginner errand", 8, new int[] {MALUS}, BEGINNER_CHEST);
        QuestData golden = QuestFixtures.data("Golden cache", 8, new int[] {FESTIVAL_TOKEN, FESTIVAL_TOKEN}, GOLDEN_CHEST);
        QuestData swap = QuestFixtures.data("Token swap", 5, new int[0], FESTIVAL_TOKEN);
        QuestData unknown = QuestFixtures.data("Unknown loot", 8, null); unknown.rewards = null;
        return new QuestData[] {royal, cultist, festival, haul, delivery, errand, golden, swap, unknown};
    }

    private static int[] repeat(int id, int count) { int[] ids = new int[count]; Arrays.fill(ids, id); return ids; }

    /** The account in game's plan: the Wizard's goals (CharacterFixtures) and four quest plans with confirmed stock. */
    public static PlanData.AccountPlan plan(PlanData.AccountPlan before, long now) throws Exception {
        PlanData.AccountPlan plan = CharacterFixtures.goals(before, CharacterFixtures.definitions(), PlanningMetadata.current(), now);
        for (String name : List.of("Royal tribute", "Festival exchange", "Cultist tribute", "Beginner errand")) put(plan, name, now);
        plan.quests.get("Festival exchange").desiredRepeats = 3;
        QuestPlanning.held(plan, FORGOTTEN_KING, 12, "Synthetic count; vault checked manually", false, now - 30 * MINUTE);
        QuestPlanning.held(plan, FESTIVAL_TOKEN, 4, "Synthetic count", false, now - 30 * MINUTE);
        QuestPlanning.held(plan, MALUS, 1, "", false, now - 30 * MINUTE);
        return plan;
    }

    /** A plan entry exactly as "Add to account plan" snapshots the captured quest. */
    private static void put(PlanData.AccountPlan plan, String name, long now) throws Exception {
        QuestData quest = null;
        for (QuestData q : quests()) if (q.name.equals(name)) quest = q;
        Class<?> copy = Class.forName("tomato.gui.quest.QuestGUI$Quest");
        Constructor<?> constructor = copy.getDeclaredConstructor(QuestData.class); constructor.setAccessible(true);
        Method snapshot = QuestPlanning.class.getMethod("snapshot", copy, long.class, long.class);
        PlanData.QuestPlanEntry entry = (PlanData.QuestPlanEntry) snapshot.invoke(null, constructor.newInstance(quest), now - 20 * MINUTE, 1L);
        plan.quests.put(entry.entryId, entry);
    }

    // ---- Bridge Review over a fake service ----

    /**
     * A configured bridge over a synthetic in-process transport (no endpoint is contacted; "example.invalid" never resolves): four
     * detected drops, one logged, one skipped as unmapped and two local. Close it after use.
     */
    public static BridgeService bridge(Path folder) throws Exception {
        Path csv = folder.resolve("bridge-items.csv");
        Files.write(csv, "Item Name\nSynthetic Voidblade\nSynthetic Frost Staff\nSynthetic Tier Sword\n".getBytes(StandardCharsets.UTF_8));
        Properties p = new Properties(); String x = BridgeConfig.PREFIX;
        p.setProperty(x + "enabled", "true"); p.setProperty(x + "send", "true"); p.setProperty(x + "endpoint", "https://example.invalid/ingest");
        p.setProperty(x + "guild_id", "123456789012345678"); p.setProperty(x + "link_token", "synthetic-fixture-token"); p.setProperty(x + "csv_path", csv.toString());
        p.setProperty(x + "debug", "true");
        BridgeService service = new BridgeService(folder.resolve("bridge-fixture.properties"), false, (url, json) ->
            new BridgeService.Response(200, json.contains("Synthetic Frost Staff") ? "{\"ok\":true}"
                : json.contains("Synthetic Tier Sword") ? "{\"result\":{\"logged\":false,\"reason\":\"unmapped_character\"}}" : "{\"ok\":true,\"result\":{\"logged\":true}}"), 20);
        service.configure(new BridgeConfig(p), false, false);
        service.receive(Arrays.asList(bridgeDrop(42, "Synthetic Voidblade", 7, "Alpha", "Lost Halls", "Damage Boost(1)"),
            bridgeDrop(43, "Synthetic Aegis Robe", 7, "Alpha", "Lost Halls", ""), bridgeDrop(44, "Synthetic Frost Staff", 8, "Charlie", "Ice Citadel", ""),
            bridgeDrop(45, "Synthetic Tier Sword", 8, "Charlie", "Lost Halls", "")));
        service.awaitIdle(3000);
        return service;
    }

    private static BridgePayload.Drop bridgeDrop(int id, String name, int character, String who, String dungeon, String enchants) {
        return new BridgePayload.Drop(new BridgePayload.Item(id, name, "EQUIPMENT", "UT", enchants, false), character, who, "Wizard", dungeon, false, false, 9, 0);
    }
}
