package tomato.gui.runs;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.function.LongSupplier;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DamageSource;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.activity.ActivityRoutes;
import tomato.gui.activity.ActivitySummaries;
import tomato.gui.activity.RunWorkbench;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.KitFormat;
import tomato.gui.kit.Portals;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.LootFacts;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatDetail;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;

/**
 * Builds the {@link RunRecapModel} of one exact saved run. Off the EDT only; one build reads the catalog once, the run's own
 * {@code runs} checkpoint ({@link SessionStore#readCheckpoint}, then {@code normalizePlayers()}), and the run's session's loot
 * bags, fame readings, combat records and timeline events, keeping only those that carry exactly this {@link VisitRef} (the
 * timeline: this session's events with this visit id), plus the saved detail of the one recording shown. Nothing is joined
 * by name, map or time.
 *
 * <p>The visit is closed as the readers close it: {@code readCheckpoint} applies no fix-up, so a visit its session left open
 * (a session that saved its end, or an earlier launch that crashed) is closed here at its last-seen time with
 * {@link RunOutcome#APP_ENDED_REASON}, and its outcome is {@link RunOutcome#of} with the session's state from the catalog.
 *
 * <p>A missing visit gives an unavailable model with the Runs archive's wording. A module that cannot be read gives its section
 * a reason and its tiles "—"; it never fails the recap. Cancellation is checked between reads.
 */
public final class RunRecapBuilder {
    /** The Damage section's and tiles' reason when no recording is linked to the run (spec §6.3's wording). */
    public static final String NO_RECORDING = "No combat recording is linked to this run.";
    /** {@code RecordedEncounter.unavailableReason()}'s wording for a recording whose local row was not verified. */
    public static final String LOCAL_UNVERIFIED = "The local player's row was not verified for this encounter; another player's row is never substituted.";
    /** The Runs archive's wording for an exact visit without saved Timeline events. */
    static final String NO_EVENTS = "No saved Timeline events for this exact visit. Recording coverage is unknown; an empty window is not proof that nothing happened.";
    /** At most this many Timeline events are kept in the model; {@code Timeline.total} counts all of them. */
    static final int EVENT_LIMIT = 500;
    /** Longest first: the window, then the entry, then the recording id, each unknown last ({@link CombatFacts#longest}'s rule). */
    private static final Comparator<CombatRecord> LONGEST_FIRST = Comparator
        .comparing(RunRecapBuilder::window, Comparator.nullsFirst(Comparator.<Double>naturalOrder()))
        .thenComparing(r -> r.enteredAt, Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
        .thenComparing(r -> r.recordingId, Comparator.nullsFirst(Comparator.<String>naturalOrder())).reversed();
    private static final StatType[] SLOTS = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};

    private final SessionStore store;
    private final ZoneId zone;
    private final LongSupplier clock;

    /** {@code zone} formats the Evidence text's times; {@code clock} stamps the model ({@link RunRecapModel#capturedAt}). */
    public RunRecapBuilder(SessionStore store, ZoneId zone, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The recap of {@code ref}, showing the recording {@code recordingId} in the Damage section when it is one of this run's,
     * else the longest (null asks for the longest). Tiles always use the longest recording, as cards and Home do.
     *
     * @throws IllegalStateException on the EDT
     * @throws java.util.concurrent.CancellationException when {@code cancel} is cancelled
     * @throws IOException when the catalog or the visit's own checkpoint cannot be read (a damaged file is not an absence)
     */
    public RunRecapModel build(VisitRef ref, String recordingId, Cancellation cancel) throws IOException {
        Objects.requireNonNull(ref, "ref");
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        Cancellation token = cancel == null ? new Cancellation() : cancel;
        token.check();
        long now = clock.getAsLong();
        if (!ActivityRoutes.queryable(ref)) return RunRecapModel.unavailable(ref, unavailable(ref), now);
        List<SessionStore.SessionEntry> catalog = store.catalog(token);
        SessionStore.SessionEntry entry = null;
        for (SessionStore.SessionEntry candidate : catalog) if (candidate.id.equals(ref.sessionId)) entry = candidate;
        // An unreadable session is skipped by every archive read, so its runs are not in this saved history either.
        if (entry == null || !entry.readable()) return RunRecapModel.unavailable(ref, unavailable(ref), now);
        token.check();
        ActivityJournal.Visit visit = store.readCheckpoint(ref.sessionId, "runs", ref.visitId, ActivityJournal.Visit.class).orElse(null);
        if (visit == null || !ref.visitId.equals(visit.id)) return RunRecapModel.unavailable(ref, unavailable(ref), now);
        visit.normalizePlayers();
        SessionStore.Session session = entry.session();
        boolean ended = session.ended > 0, current = ref.sessionId.equals(store.currentId());
        if (visit.ended == 0 && (ended || !current)) {   // as SessionStore.read (saved end) and Home (crashed launch) close it
            visit.ended = ended ? visit.lastSeen : Math.max(visit.started, visit.lastSeen);
            visit.endReason = RunOutcome.APP_ENDED_REASON;
        }
        RunOutcome outcome = RunOutcome.of(visit, ended, current);
        ActivityQueries.Row row = ActivityQueries.visit(visit);

        token.check();
        Facts facts = new Facts();
        facts.read(store, catalog, entry, ref, token);

        CombatRecord longest = CombatFacts.longest(facts.records);
        CombatRecord shown = longest;
        for (CombatRecord record : facts.records) if (record.recordingId.equals(recordingId)) shown = record;
        CombatDetail detail = null;
        String detailReason = null;
        if (shown != null) {
            token.check();
            try { detail = CombatFacts.detail(store, ref.sessionId, shown.recordingId); }
            catch (IOException | JsonParseException unreadable) { detailReason = "The saved detail of this recording could not be read, so its damage over time and by source are not shown."; }
            if (detail == null && detailReason == null)
                detailReason = "The damage over time and by source of this recording were not saved; only its totals are.";
        }
        token.check();

        String character = character(facts.fame, ref);
        RunRecapModel.Header header = new RunRecapModel.Header(visit.map, visit.map == null || visit.map.isEmpty() ? "Unknown area" : visit.map,
            Portals.spriteId(visit.map), outcome, row.time, row.durationMillis, visit.rosterSize, character);
        RunRecapModel.Loot loot = loot(facts);
        List<RunRecapModel.Tile> tiles = new ArrayList<>();
        combatTiles(tiles, longest, facts);
        tiles.add(fameTile(facts, ref));
        tiles.add(lootTile(loot, facts));
        tiles.add(visit.exaltIncrease > 0
            ? new RunRecapModel.Tile(RunRecapModel.Tile.EXALT, "Exalt progress",
                DisplayValue.known("+" + DisplayFormat.formatInteger(visit.exaltIncrease), "Progress increase observed within this visit"), null)
            : new RunRecapModel.Tile(RunRecapModel.Tile.EXALT, "Exalt progress",
                DisplayValue.unknown("No exalt progress was observed in this run; without that evidence no zero is shown."), null));

        return new RunRecapModel(ref, null, now, header, tiles, damage(facts, shown, detail, detailReason), loot, players(visit), resources(visit),
            timeline(facts), RunWorkbench.text(RunWorkbench.sections(ref, row, visit, zone)));
    }

    /** The Runs archive's wording for an exact reference that is not in saved history; nothing is substituted. */
    static String unavailable(VisitRef ref) {
        return "Linked visit unavailable: session " + ref.sessionId + " · visit " + ref.visitId + " is not in this saved history."
            + "\nIt may belong to an imported, deleted or another machine's session, or it has not been saved yet. "
            + "No other visit (including one with the same dungeon name) is substituted.";
    }

    /** The run's session facts, already filtered to its exact visit; a {@code …Failure} is set when that module could not be read. */
    private static final class Facts {
        final List<CombatRecord> records = new ArrayList<>();
        final List<LootFacts.Bag> bags = new ArrayList<>();
        final List<AppHistory.FameSample> fame = new ArrayList<>();
        final List<ActivityJournal.Entry> events = new ArrayList<>();
        boolean lootSaved;
        SessionStore.ModuleAvailability runs;
        String combatFailure, lootFailure, fameFailure, timelineFailure;

        void read(SessionStore store, List<SessionStore.SessionEntry> catalog, SessionStore.SessionEntry entry, VisitRef ref, Cancellation token) {
            String session = ref.sessionId;
            runs = entry.availability("runs");
            try { CombatFacts.read(store, catalog, session, record -> { if (ref.equals(record.visit())) records.add(record); }); }
            catch (IOException | JsonParseException failure) { records.clear(); combatFailure = "Combat recordings could not be read from saved history."; }
            token.check();
            try { LootFacts.read(store, catalog, session, bag -> { lootSaved = true; if (ref.equals(bag.visit())) bags.add(bag); }); }
            catch (IOException | JsonParseException failure) { bags.clear(); lootFailure = "Loot could not be read from saved history."; }
            token.check();
            try {
                store.read(catalog, session, "fame", AppHistory.FameSample.class, (s, sample) -> fame.add(sample));
                store.read(catalog, session, "fame-latest", AppHistory.FameSample.class, (s, sample) -> fame.add(sample));
            } catch (IOException | JsonParseException failure) { fame.clear(); fameFailure = "Fame readings could not be read from saved history."; }
            token.check();
            try {
                store.read(catalog, session, "timeline", ActivityJournal.Entry.class, (s, event) -> {
                    if (event != null && ref.visitId.equals(event.visitId)) events.add(event);
                });
            } catch (IOException | JsonParseException failure) { events.clear(); timelineFailure = "Timeline events could not be read from saved history."; }
            token.check();
            bags.sort(Comparator.comparingLong(LootFacts.Bag::time));   // stable: drop order at equal times
            events.sort(Comparator.comparingLong(e -> e.time));
        }
    }

    /** "Wizard #3" for each distinct character of the readings tagged with this run, in time order; null when none. */
    private static String character(List<AppHistory.FameSample> samples, VisitRef ref) {
        List<AppHistory.FameSample> tagged = new ArrayList<>();
        for (AppHistory.FameSample sample : samples) if (sample != null && ref.equals(sample.visit())) tagged.add(sample);
        tagged.sort(Comparator.comparingLong(s -> s.time));
        Set<String> names = new LinkedHashSet<>();
        for (AppHistory.FameSample sample : tagged)
            names.add((sample.className == null || sample.className.isBlank() ? "Character" : sample.className) + " #" + sample.character);
        return names.isEmpty() ? null : String.join(", ", names);
    }

    // ---- tiles ----

    /** Your DPS with rank, damage share and deaths, from the longest recording's verified local row only. */
    private static void combatTiles(List<RunRecapModel.Tile> tiles, CombatRecord longest, Facts facts) {
        String reason = longest == null ? facts.combatFailure != null ? facts.combatFailure : NO_RECORDING
            : longest.localObjectId == null ? LOCAL_UNVERIFIED : null;
        if (reason != null) {
            tiles.add(new RunRecapModel.Tile(RunRecapModel.Tile.DPS, "Your DPS", DisplayValue.unknown(reason), null));
            tiles.add(new RunRecapModel.Tile(RunRecapModel.Tile.SHARE, "Damage share", DisplayValue.unknown(reason), null));
            tiles.add(new RunRecapModel.Tile(RunRecapModel.Tile.DEATHS, "Deaths", DisplayValue.unknown(reason), null));
            return;
        }
        int recordings = facts.records.size();
        String of = recordings > 1 ? "the longest of " + recordings + " recordings linked to this run" : "the recording linked to this run";
        CombatRecord.PlayerLine local = longest.local();   // null: verified, but no recorded damage
        long damage = longest.localDamage();
        DisplayValue dps;
        if (longest.windowSeconds == null || !(longest.windowSeconds > 0) || longest.windowSeconds.isInfinite())
            dps = DisplayValue.unknown("No first-to-last hit window was recorded in " + of + ", so DPS is unknown.");
        else {
            String source = "Your verified row in " + of + ": " + DisplayFormat.formatInteger(damage) + " damage over its "
                + DisplayFormat.formatNumber(longest.windowSeconds, 1) + " s first-to-last hit window";
            dps = damage == 0 ? DisplayValue.zero(source) : DisplayValue.known(KitFormat.compact(damage / longest.windowSeconds), source);
        }
        tiles.add(new RunRecapModel.Tile(RunRecapModel.Tile.DPS, "Your DPS", dps,
            local == null ? "No recorded damage" : "#" + local.rank + " of " + longest.contributors));
        DisplayValue share;
        if (longest.totalDamage <= 0) share = DisplayValue.unknown("No damage was recorded in " + of + ".");
        else {
            String source = "Your share of all recorded damage in " + of + ", unattributed hits included";
            share = damage == 0 ? DisplayValue.zero(source) : DisplayValue.known(DisplayFormat.formatNumber(damage * 100.0 / longest.totalDamage, 0, 1) + "%", source);
        }
        tiles.add(new RunRecapModel.Tile(RunRecapModel.Tile.SHARE, "Damage share", share, null));
        Integer deaths = local == null ? null : local.deaths;
        tiles.add(new RunRecapModel.Tile(RunRecapModel.Tile.DEATHS, "Deaths",
            DisplayValue.count(deaths == null ? null : deaths.longValue(), "Death notifications naming you in " + of, local == null
                ? "Your verified character has no row in " + of + ", so no death notification can be matched to it."
                : "Deaths are matched by name inside one recording only; your name there is unknown or shared with another player."),
            "All players: " + DisplayFormat.formatInteger(longest.deaths)));
    }

    private static RunRecapModel.Tile fameTile(Facts facts, VisitRef ref) {
        DisplayValue value;
        if (facts.fameFailure != null) value = DisplayValue.unknown(facts.fameFailure);
        else {
            Optional<Long> gain = FameGains.of(FameGains.byVisit(facts.fame, ref.sessionId, facts.runs), ref);
            boolean tagged = false;
            for (AppHistory.FameSample sample : facts.fame) if (sample != null && ref.equals(sample.visit())) tagged = true;
            String source = "Fame increases of your character recorded during this run, each against the reading before it";
            if (gain.isPresent()) value = gain.get() == 0 ? DisplayValue.zero(source) : DisplayValue.known("+" + DisplayFormat.formatInteger(gain.get()), source);
            else value = DisplayValue.unknown(tagged
                ? "Fame gained is unknown: a fame reading of this run has no earlier reading of the same character within one unbroken capture."
                : "No fame reading was recorded during this run (readings are saved only when fame changes), so its fame gained is unknown.");
        }
        return new RunRecapModel.Tile(RunRecapModel.Tile.FAME, "Fame", value, null);
    }

    private static RunRecapModel.Tile lootTile(RunRecapModel.Loot loot, Facts facts) {
        DisplayValue value;
        if (facts.lootFailure != null) value = DisplayValue.unknown(facts.lootFailure);
        else if (!facts.lootSaved) value = DisplayValue.unknown(loot.reason());
        else value = DisplayValue.count((long) loot.count(), "Items in bags recorded inside this exact run", null);
        String subline = loot.bags().isEmpty() || loot.summary().isEmpty() ? null : loot.summary();
        return new RunRecapModel.Tile(RunRecapModel.Tile.LOOT, "Loot", value, subline);
    }

    // ---- sections ----

    private static RunRecapModel.Damage damage(Facts facts, CombatRecord shown, CombatDetail detail, String detailReason) {
        List<CombatRecord> ordered = new ArrayList<>(facts.records);
        ordered.sort(LONGEST_FIRST);
        List<RunRecapModel.Damage.Recording> recordings = new ArrayList<>();
        for (CombatRecord record : ordered) recordings.add(new RunRecapModel.Damage.Recording(record.recordingId, record.enteredAt, window(record), record.contributors));
        if (shown == null)
            return new RunRecapModel.Damage(recordings, null, List.of(), List.of(), 1, 0, 0, 0, null, 0,
                facts.combatFailure != null ? facts.combatFailure : NO_RECORDING, null, null);
        Map<Integer, CombatDetail.PlayerSources> sources = new HashMap<>();
        Map<Integer, CombatRecord.PlayerLine> lines = new HashMap<>();
        if (detail != null) for (CombatDetail.PlayerSources player : detail.sources) if (player != null) sources.putIfAbsent(player.objectId, player);
        List<RunRecapModel.Damage.Row> rows = new ArrayList<>();
        boolean local = false;
        for (CombatRecord.PlayerLine line : shown.players) {
            if (line == null) continue;
            lines.putIfAbsent(line.objectId, line);
            boolean mine = line.local && shown.localObjectId != null && line.objectId == shown.localObjectId;
            local |= mine;
            rows.add(new RunRecapModel.Damage.Row(line.objectId, line.name, line.classType, line.damage, shown.dps(line), shown.share(line),
                line.hits, line.maxHit, line.taken, line.deaths, mine, line.rank, sources(sources.get(line.objectId))));
        }
        List<RunRecapModel.Damage.Series> series = new ArrayList<>();
        if (detail != null) for (CombatDetail.Series values : detail.series) {
            if (values == null) continue;
            CombatRecord.PlayerLine line = lines.get(values.objectId);
            boolean mine = values.local && shown.localObjectId != null && values.objectId == shown.localObjectId;
            series.add(new RunRecapModel.Damage.Series(values.objectId, line == null ? null : line.name, line == null ? 0 : line.classType, mine, values.values));
        }
        String localReason = local ? null : shown.localObjectId == null ? LOCAL_UNVERIFIED
            : "Your verified character has no recorded damage in this recording.";
        return new RunRecapModel.Damage(recordings, shown.recordingId, rows, series, detail == null ? 1 : Math.max(1, detail.bucketSeconds),
            detail == null ? 0 : detail.bucketOrigin, shown.totalDamage, shown.unattributedDamage, window(shown), shown.hitsBeforeFirstTick,
            rows.isEmpty() ? "No player damage was recorded in this recording." : null, localReason, detailReason);
    }

    private static List<RunRecapModel.Damage.Source> sources(CombatDetail.PlayerSources player) {
        if (player == null || player.sources == null) return List.of();
        List<RunRecapModel.Damage.Source> result = new ArrayList<>();
        for (CombatDetail.SourceLine line : player.sources) {
            if (line == null) continue;
            List<RunRecapModel.Damage.Item> items = new ArrayList<>();
            if (line.items != null) for (CombatDetail.ItemLine item : line.items) if (item != null) items.add(new RunRecapModel.Damage.Item(item.itemId, item.damage, item.hits));
            result.add(new RunRecapModel.Damage.Source(line.source, label(line.source), line.damage, line.hits, items));
        }
        return result;
    }

    /** The source's display label; a name this build does not know (a newer file) is shown as saved. */
    private static String label(String source) {
        if (source == null) return DamageSource.UNKNOWN.label;
        try { return DamageSource.valueOf(source).label; } catch (IllegalArgumentException newer) { return source; }
    }

    private static RunRecapModel.Loot loot(Facts facts) {
        List<RunRecapModel.Loot.Bag> bags = new ArrayList<>();
        int count = 0;
        for (LootFacts.Bag bag : facts.bags) {
            bags.add(new RunRecapModel.Loot.Bag(bag.bag(), bag.time(), bag.items()));
            count += bag.items().size();
        }
        String reason = !bags.isEmpty() ? null : facts.lootFailure != null ? facts.lootFailure
            : facts.lootSaved ? "No loot bag was recorded inside this exact run; drops of other runs or without a recorded run are never shown here."
            : "No loot bag was saved in this run's session, so its loot is unknown.";
        // The feed card's wording, so a run reads the same on its card and in its recap.
        return new RunRecapModel.Loot(bags, count, RunCardModel.summary(facts.bags), reason);
    }

    private static RunRecapModel.Players players(ActivityJournal.Visit visit) {
        List<RunRecapModel.Players.Player> players = new ArrayList<>();
        for (Map.Entry<String, InspectSnapshot> entry : visit.inspectedPlayers.entrySet()) {
            InspectSnapshot snapshot = entry.getValue();
            if (snapshot == null || !snapshot.isValid()) continue;
            Entity player = snapshot.toEntity();
            String name = player.name();
            List<Integer> equipment = new ArrayList<>(SLOTS.length);
            for (StatType slot : SLOTS) { StatData item = player.stat.get(slot); equipment.add(item == null ? null : item.statValue); }
            players.add(new RunRecapModel.Players.Player(snapshot.objectId(), name == null || name.isEmpty() ? null : name, snapshot.className(),
                player.objectType, equipment, visit.damage(entry.getKey()), snapshot.observedAt()));
        }
        // Most Inspect damage first (unknown last); the saved order otherwise.
        players.sort(Comparator.comparing(RunRecapModel.Players.Player::inspectDamage, Comparator.nullsLast(Comparator.<Long>reverseOrder())));
        return new RunRecapModel.Players(players, visit.inspectedPlayerCount,
            players.isEmpty() ? "No player loadouts were recorded in this run." : null,
            visit.damageTracked ? null : "Inspect damage was not tracked for this run.");
    }

    private static RunRecapModel.Resources resources(ActivityJournal.Visit visit) {
        return visit.resourceTimeline.isEmpty() && visit.conditionTimeline.isEmpty()
            ? new RunRecapModel.Resources(null, "No resource or buff samples were saved for this run. Old aggregate-only visits cannot reconstruct charts.")
            : new RunRecapModel.Resources(visit, null);
    }

    private static RunRecapModel.Timeline timeline(Facts facts) {
        if (facts.timelineFailure != null) return new RunRecapModel.Timeline(List.of(), 0, facts.timelineFailure);
        List<RunRecapModel.Timeline.Event> events = new ArrayList<>();
        for (ActivityJournal.Entry event : facts.events) {
            if (events.size() == EVENT_LIMIT) break;
            events.add(new RunRecapModel.Timeline.Event(event.time, Objects.toString(event.kind, "Unknown activity"), ActivitySummaries.event(event)));
        }
        return new RunRecapModel.Timeline(events, facts.events.size(), events.isEmpty() ? NO_EVENTS : null);
    }

    private static Double window(CombatRecord record) {
        Double window = record.windowSeconds;
        return window != null && window > 0 && !window.isInfinite() ? window : null;
    }
}
