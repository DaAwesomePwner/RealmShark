package tomato.gui.glance.home;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import packets.data.QuestData;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.Tomato;
import tomato.backend.data.*;
import tomato.gui.dps.DpsGUI;
import tomato.gui.dps.MeterSummary;
import tomato.gui.dps.RecordedEncounter;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.myinfo.BuildEstimates;
import tomato.gui.quest.QuestPinning;
import tomato.history.SessionStore;
import tomato.history.link.EncounterContext;

/**
 * Production Home sources: live capture state plus saved history. HomeRefresher calls revisions(), hero(), now() and
 * quests() only on "home-refresh" and archive() only on "home-archive"; each thread's memos below are its own.
 */
public final class LiveHomeSources implements HomeSources {
    private final TomatoData data;
    private final Supplier<SessionStore> store;
    private final BiPredicate<String, QuestData> pins;
    private final Supplier<String> recordingsRevision;
    private final Function<Map<String, RecordedEncounter>, List<RecordedEncounter>> recordings;
    private final AtomicLong progressionChanges = new AtomicLong();
    // "home-refresh": the section tokens and the memos that make a rebuild cheap.
    private final Token heroToken = new Token(), nowToken = new Token(), questToken = new Token();
    private LiveCharacter.Snapshot estimatedFor;
    private BuildEstimates.Estimates estimates;
    private DpsSnapshot summarized;
    private MeterSummary summary = MeterSummary.EMPTY;
    private final Map<List<Object>, Boolean> pinned = new HashMap<>();   // by quest ID, name and category
    private long pinsReadFor = -1;
    // "home-archive": per-session archive facts kept between reads, and the recorded meters, each recording projected once.
    private final HomeArchive.Cache archiveCache;
    private final Map<String, RecordedEncounter> projected = new HashMap<>();
    private String recordedRevision;
    private List<RecordedEncounter> recorded = List.of();

    public LiveHomeSources(TomatoData data, Supplier<SessionStore> store) {
        this(data, store, QuestPinning::pinned, DpsGUI::recordingsRevision, known -> DpsGUI.recordedEncounters(known), new HomeArchive.Cache());
    }

    /** Tests replace the Preferences pin lookup and the DPS page's recordings (recomputed as a whole). */
    LiveHomeSources(TomatoData data, Supplier<SessionStore> store, BiPredicate<String, QuestData> pins,
                    Supplier<String> recordingsRevision, Supplier<List<RecordedEncounter>> recordings) {
        this(data, store, pins, recordingsRevision, known -> recordings.get(), new HomeArchive.Cache());
    }

    /** {@code recordings} receives the projections kept so far (by catalog entry) and replaces them, as DpsGUI.recordedEncounters does. */
    LiveHomeSources(TomatoData data, Supplier<SessionStore> store, BiPredicate<String, QuestData> pins, Supplier<String> recordingsRevision,
                    Function<Map<String, RecordedEncounter>, List<RecordedEncounter>> recordings, HomeArchive.Cache cache) {
        this.data = Objects.requireNonNull(data, "data");
        this.store = Objects.requireNonNull(store, "store");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.recordingsRevision = Objects.requireNonNull(recordingsRevision, "recordingsRevision");
        this.recordings = Objects.requireNonNull(recordings, "recordings");
        this.archiveCache = Objects.requireNonNull(cache, "cache");
        data.progression().addListener(progressionChanges::incrementAndGet);   // Home lives as long as the app
    }

    /** A counter that moves whenever its key (compared with equals; snapshots by identity) changes. */
    private static final class Token {
        private Object key;
        private long value;
        long of(Object next) {
            if (value == 0 || !Objects.equals(next, key)) { key = next; value++; }
            return value;
        }
    }

    @Override public Revisions revisions() {
        LiveCharacter live = data.liveCharacter;
        boolean graceOver = live.current() == null && live.lastKnown() != null
            && !HomeModelBuilder.stillCurrent(live.lastSeenAt(), live.lastBoundary(), System.currentTimeMillis());
        DiscoveryLog.CurrentVisit visit = DiscoveryLog.INSTANCE.currentVisit();
        return new Revisions(
            heroToken.of(Arrays.asList(live.revision(), data.characterJournal().revision(), graceOver)),
            nowToken.of(Arrays.asList(Tomato.isCaptureRunning(), visit == null ? null : visit.visit, visit == null ? null : visit.map,
                visit == null ? 0L : visit.started, DpsGUI.latestSnapshot(), KeypopGUI.popRevision())),
            questToken.of(Arrays.asList(progressionChanges.get(), QuestPinning.revision())));
    }

    /**
     * The hero on "home-refresh". A live or last-known hero's pet chip comes from its own journal record, read here by the
     * snapshot's journal key (journal::characterCopy, a deep copy); the saved fallback's from the record already in hand. The
     * hero token already includes the journal revision, so a new pet reading rebuilds the hero.
     */
    @Override public HomeModel.Hero hero(long now) {
        LiveCharacter live = data.liveCharacter;
        CharacterJournal journal = data.characterJournal();
        CharacterJournal.CharacterRecord last = journal.mostRecentCharacter();
        LiveCharacter.Snapshot current = live.current();
        if (current != null)
            return HomeModelBuilder.hero(current, estimates(current), last, account(journal, current.account()), journal::characterCopy, 0, null, now);
        LiveCharacter.Snapshot known = live.lastKnown();
        if (known == null && !journal.readable())
            return HomeModel.Hero.placeholder(HomeModel.State.UNAVAILABLE, journal.storageStatus());
        long seen = known == null ? 0 : live.lastSeenAt() > 0 ? live.lastSeenAt() : Math.max(1, known.observedAt());
        String account = known != null ? known.account() : last != null ? last.account : null;
        return HomeModelBuilder.hero(known, estimates(known), last, account(journal, account), journal::characterCopy, seen, live.lastBoundary(), now);
    }
    private static CharacterJournal.AccountRecord account(CharacterJournal journal, String key) { return key == null ? null : journal.accountCopy(key); }

    /** Build's estimates once per published snapshot, from its detached copies: never on the capture thread. */
    private BuildEstimates.Estimates estimates(LiveCharacter.Snapshot snapshot) {
        if (snapshot == null) return null;
        if (snapshot != estimatedFor) { estimatedFor = snapshot; estimates = snapshot.build() == null ? null : snapshot.build().estimate(); }
        return estimates;
    }

    @Override public HomeModel.Now now(long now) {
        DiscoveryLog.CurrentVisit visit = DiscoveryLog.INSTANCE.currentVisit();
        DpsSnapshot meter = DpsGUI.latestSnapshot();
        EncounterContext context = meter == null ? null : meter.context;
        // The meter is summarized only when its encounter belongs to exactly this visit (HomeModelBuilder.now checks again).
        boolean linked = HomeModelBuilder.linked(visit == null ? null : visit.visit, context);
        return HomeModelBuilder.now(Tomato.isCaptureRunning(), visit, context, linked ? summary(meter) : MeterSummary.EMPTY, KeypopGUI.lastPop());
    }
    /** MeterSummary.of costs O(hits): once per published meter snapshot. */
    private MeterSummary summary(DpsSnapshot meter) {
        if (meter != summarized) { summarized = meter; summary = MeterSummary.of(meter, HomeModelBuilder.METER_ROWS); }
        return summary;
    }

    @Override public HomeModel.Quests quests(long now) {
        ProgressionData.Snapshot snapshot = data.progression().snapshot();
        String account = snapshot.quests == null ? null : snapshot.quests.scope.account;   // pins belong to the list's account
        // Preferences are read again only after the quests token moved (a new list or a pin change), not on the age tick.
        if (pinsReadFor != questToken.value) { pinsReadFor = questToken.value; pinned.clear(); }
        return HomeModelBuilder.quests(snapshot, quest -> account != null && pinned.computeIfAbsent(Arrays.asList(quest.id, quest.name, quest.category), key -> pins.test(account, quest)), now);
    }

    @Override public HomeArchive.Result archive(HomeArchive.Window window, long now) throws IOException {
        SessionStore history = store.get();
        if (history == null) throw new IOException("Saved history is not available yet");
        return HomeArchive.read(history, window, now, ZoneId.systemDefault(), recorded(), archiveCache);
    }
    /** Re-projected only when the recordings changed, and then only the recordings not projected before. */
    private List<RecordedEncounter> recorded() {
        String revision = recordingsRevision.get();   // before the list: a change racing this read only causes another read
        if (!revision.equals(recordedRevision)) { recorded = recordings.apply(projected); recordedRevision = revision; }
        return recorded;
    }
}
