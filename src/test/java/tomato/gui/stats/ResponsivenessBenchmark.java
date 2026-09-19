package tomato.gui.stats;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import packets.Packet;
import packets.PacketType;
import packets.data.ObjectStatusData;
import packets.data.PartyPlayerData;
import packets.data.StatData;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.IncomingPartyMemberInfoPacket;
import packets.incoming.MapInfoPacket;
import packets.incoming.NewTickPacket;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.Tomato;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.CombatTimelineChart;
import tomato.gui.logging.LoggingGUI;
import tomato.gui.modern.VioletTheme;
import tomato.realmshark.Sound;
import util.PropertiesManager;

/** Opt-in main, deliberately not a JUnit test. See stdout for workload and metric definitions. */
public final class ResponsivenessBenchmark {
    private static final int DEFAULT_SAMPLES = 100000, MAX_SAMPLES = 200000;
    private static final long EPOCH = 1704067200000L, LOGICAL_MS = 6 * 60 * 60 * 1000L;
    private static final int VISITS = 240, CHARACTERS = 4, LOGICAL_VISITS = 16;
    private static final String FINAL_MAP = "The Shatters";
    private static final int FINAL_PARTY_BASE = 1000000;
    // Functional budgets, not target-hardware timing promises.
    private static final int MAX_TIMELINE_PER_VISIT = 1000, MAX_TIMELINE_TOTAL = 12000;
    private static final int HEARTBEAT_MS = 15, HEARTBEAT_CAPACITY = 8192, P95_MIN_SAMPLES = 100;
    private static final long PHASE_MIN_MS = 2500, SOFT_RUN_MS = 30000, HARD_RUN_SECONDS = 120;
    private static final double SOFT_P95_MS = 100, SOFT_MAX_MS = 1000;
    private static final AtomicReference<Throwable> ASYNC_FAILURE = new AtomicReference<>();
    private static final Allocation ALLOCATION = new Allocation();
    private static volatile Object blackhole;
    private static long edtId;

    private ResponsivenessBenchmark() { }

    public static void main(String[] args) {
        int exit = 1;
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "benchmark-watchdog"));
        watchdog.schedule(() -> {
            System.err.println("FAIL: 120 s harness safety timeout (incomplete run, not a hardware performance verdict).");
            Runtime.getRuntime().halt(2);
        }, HARD_RUN_SECONDS, TimeUnit.SECONDS);
        try {
            int samples = samples(args);
            isolate();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> ASYNC_FAILURE.compareAndSet(null, error));
            edt(() -> { edtId = Thread.currentThread().getId(); VioletTheme.install(); });
            describe(samples);
            long started = System.nanoTime();
            // Separate fixtures prevent warmup history and counters contaminating measurement.
            fame(2000, true);
            discovery(2000, true);
            logicalActivity(2000, true);
            fame(samples, false);
            discovery(samples, false);
            logicalActivity(samples, false);
            checkAsync();
            double elapsed = millis(System.nanoTime() - started);
            out("RESULT functional assertions PASS; wall=%.1f ms; soft 30 s budget=%s", elapsed,
                elapsed <= SOFT_RUN_MS ? "within" : "exceeded (report only)");
            exit = 0;
        } catch (Throwable error) {
            error.printStackTrace(System.err);
        } finally {
            // This is a dedicated JavaExec JVM. Bound cleanup even if an EDT/worker is stuck.
            try { edt(() -> { for (java.awt.Window window : java.awt.Window.getWindows()) window.dispose(); }); }
            catch (Throwable error) { error.printStackTrace(System.err); exit = 1; }
            watchdog.shutdownNow();
        }
        System.exit(exit);
    }

    private static int samples(String[] args) {
        int value = DEFAULT_SAMPLES;
        if (args.length == 1 && args[0].startsWith("--samples=")) value = Integer.parseInt(args[0].substring(10));
        else if (args.length == 2 && "--samples".equals(args[0])) value = Integer.parseInt(args[1]);
        else if (args.length != 0) throw new IllegalArgumentException("Usage: ResponsivenessBenchmark [--samples 100000]");
        require(value >= 2000 && value <= MAX_SAMPLES, "--samples must be 2000.." + MAX_SAMPLES);
        return value;
    }

    private static void isolate() throws Exception {
        String configured = System.getProperty("realmshark.benchmark.workDir");
        require(configured != null, "Use scripts/responsiveness-validation.gradle to supply an isolated working directory");
        Path cwd = Paths.get("").toRealPath();
        require(cwd.equals(Paths.get(configured).toRealPath()), "Working directory must match the JavaExec isolation directory");
        require(!Files.exists(cwd.resolve("realmShark.properties")) && !Files.exists(cwd.resolve("logs")), "Expected fresh settings/history");
        require(!GraphicsEnvironment.isHeadless(), "A desktop display is required for real showing/hidden Swing views");
        Locale.setDefault(Locale.ROOT);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // No public preview setter exists. Only the startup flag is set reflectively; never call app main.
        Field preview = Tomato.class.getDeclaredField("preview");
        preview.setAccessible(true); preview.setBoolean(null, true);
        System.setProperty("realmshark.noSound", "true");
        Sound.setMuted(true); // Actual sound guard; the property alone is not a production mute API.
        require(PropertiesManager.flush().toCompletableFuture().get(10, TimeUnit.SECONDS).isSuccess(), "Isolated mute preference saved");
        require(Tomato.isPreview() && Sound.isMuted(), "Preview/noSound guards");
    }

    private static void describe(int samples) {
        out("Phase 2 synthetic responsiveness benchmark; samples=%d per pipeline; warmup=2000 per pipeline", samples);
        out("JVM=%s %s; OS=%s %s; processors=%d; maxHeap=%d; displayScale=%s; locale=ROOT; timezone=UTC",
            System.getProperty("java.vm.name"), System.getProperty("java.version"), System.getProperty("os.name"),
            System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
            System.getProperty("sun.java2d.uiScale", "default"));
        out("workingDir=%s; preview=true; noSound=true (master mute); no app main/capture/network", Paths.get("").toAbsolutePath());
        out("Logical clock: epoch=%d, span=6 h, %.3f ticks/s; integer timestamps; no random inputs", EPOCH, samples / 21600.0);
        out("Fame: 4 characters, 240 map visits, gain every 10 updates; FameTableBridge.observeFame; in-memory autosave completion.");
        out("Discovery: decoded NEWTICK with 4 local stats + rotating remote object; 240 MAPINFO/CREATE_SUCCESS pairs; Detailed sampling; DiscoveryLog(null).");
        out("Observer replay ends in unique map '%s' and a final party-roster marker (partyId=%d + samples); included in producer work/frame count.", FINAL_MAP, FINAL_PARTY_BASE);
        out("Fame/discovery: hidden first half, visible second half, >=2.5 s paced window per measured half; 15 ms EDT probes include showing/catch-up.");
        out("UI: Violet theme, 1440x900 window, both fame views / all four discovery views showing simultaneously (synthetic stress layout).");
        out("Discovery wall timestamps are NOT six-hour observation time: separate public ActivityJournal replay uses the logical clock and 16 long visits.");
        out("Scope: synthetic decoded-observer and fame-API throughput, not decoding/capture/end-to-end throughput or six hours of real execution.");
        out("Producer active time includes payload construction, boundary/autosave work and model lock waits, excludes batch pacing; wall throughput includes pacing.");
        out("EDT latency=enqueue-to-dispatch; nearest-rank median/p95/max; one outstanding probe, skipped ticks reported (coordinated omission disclosed).");
        out("Heartbeat storage cap=%d (no per-event latency buffer); p95 with <%d observations is marked insufficient.", HEARTBEAT_CAPACITY, P95_MIN_SAMPLES);
        out("Allocation: %s; approximate bytes on main/producer + AWT EDT, including harness overhead; excludes SwingWorker, timer thread, GC/native and other workers.", ALLOCATION.status);
        out("Heap before/after requested GC is process-wide observational used heap, not precise retained/model cost; explicit GC may be ignored.");
        out("Hard functional budgets: table last samples<=4; histories<=generated workload; activity visits<=200/events<=1000;");
        out("  diagnostic events<=1500/deltas<=24; each timeline<=1000/visit and <=12000 total; hidden render/snapshot work=0; unchanged revision copies=0.");
        out("Timeline caps are upper-bound assertions for every --samples value; saturation is reported, not required (1000 ms resource sampling depends on tick alignment).");
        out("Report-only budgets: heartbeat p95<=100 ms/max<=1000 ms; total wall<=30 s. Safety limits: 10 s EDT/drain wait; 120 s JVM watchdog.");
        out("Fame pending is a boolean flag: report batch samples/set samples/final flags, NOT measured queue depth or proof of one callback.");
        out("One-callback behavior is covered separately by FameTrackingStateTest.blockedEdtHasOneRefreshPerViewAndHiddenViewsCatchUpOnShow.");
        out("Discovery private worker queue depth is unavailable via public APIs. Only the harness's own outstanding heartbeat is bounded here.");
        out("Discovery catch-up checks final snapshot content independently in Runs/Timeline/Combat; Combat also checks an explicitly selected older retained visit.");
        out("Copy counters count copied rows/references, not bytes; pinned export materialization is outside ActivityJournal's observer-owned counters.");
        out("Fame graph/session samples and visits are workload-bounded, not claimed to have a production lifetime retention cap.");
        out("Stalled I/O coverage belongs to PreferencesStoreTest, BridgeResponsivenessTest and LootDeliveryTest; this run measures tracking/view pipelines.");
    }

    private static void fame(int samples, boolean warmup) throws Exception {
        FameFixture fixture = new FameFixture();
        edt(fixture::create);
        String prefix = warmup ? "warmup/fame" : "fame";
        try {
            if (!warmup) heap(prefix + "/baseline");
            int half = samples / 2;
            IntConsumer update = i -> fixture.update(i, samples);
            produce(prefix + "/hidden", 0, half, warmup, update, fixture::samplePendingFlags);
            require(fixture.table.trackingCounts().snapshots == 0 && fixture.graph.trackingCounts().snapshots == 0, "Hidden fame does not snapshot presentation");
            require(fixture.table.refreshCounts().rendered == 0 && fixture.graph.refreshCounts().rendered == 0, "Hidden fame does not render");
            fixture.checkRetention(half, samples);
            produce(prefix + "/visible", half, samples, warmup, update, fixture::samplePendingFlags, () -> fixture.frame.setVisible(true));
            await(() -> fixture.table.refreshCounts().pending == 0 && fixture.graph.refreshCounts().pending == 0, "Fame pending flags clear");
            require(fixture.table.refreshCounts().rendered > 0 && fixture.graph.refreshCounts().rendered > 0, "Fame catches up on show");
            fixture.checkRetention(samples, samples);
            edt(() -> {
                require(fixture.table.isShowing() && fixture.graph.isShowing(), "Real fame views showing");
                JTable table = named(fixture.table, "fame-characters", JTable.class);
                require(table.getRowCount() == CHARACTERS, "Visible fame rows catch up");
                boolean latest = false;
                for (int row = 0; row < table.getRowCount(); row++) {
                    if (((Number)table.getValueAt(row, 3)).longValue() == fameValue(samples - 1, samples)) latest = true;
                }
                require(latest, "Visible fame table contains final value");
                java.util.List<Fame> scores = find(fixture.graph, GraphPanel.class).getScores();
                require(!scores.isEmpty() && scores.get(scores.size() - 1).getFame() == fameValue(samples - 1, samples), "Graph contains final value");
                fixture.frame.setVisible(false);
            });
            FameTrackingModel.TableCounts table = fixture.table.trackingCounts();
            FameTrackingModel.HistoryCounts graph = fixture.graph.trackingCounts();
            out("%s retained: chars=%d lastSamples=%d visits=%d+%d graphSamples=%d; autosaves=%d (model save preparation included; async saver/serialization/disk excluded)",
                prefix, table.characters, table.lastSamples, table.closedVisits, table.openVisits, graph.samples, fixture.saves);
            out("%s production copies: tableSnapshots=%d visitRows=%d graphSnapshots=%d sampleRefs=%d; scheduled/rendered counters table=%d/%d graph=%d/%d",
                prefix, table.snapshots, table.copiedVisits, graph.snapshots, graph.copiedSamples,
                fixture.table.refreshCounts().scheduled, fixture.table.refreshCounts().rendered,
                fixture.graph.refreshCounts().scheduled, fixture.graph.refreshCounts().rendered);
            out("%s pending FLAGS: batchSamples=%d setSamples table=%d graph=%d finalFlags table=%s graph=%s (not queue-depth instrumentation)",
                prefix, fixture.pendingFlagSamples, fixture.tableFlagSetSamples, fixture.graphFlagSetSamples,
                fixture.table.refreshCounts().pending != 0, fixture.graph.refreshCounts().pending != 0);
            if (!warmup) {
                heap(prefix + "/retained-after-workload");
                timed("fame/full-table", fixture.table::trackingSnapshot);
                timed("fame/full-history", fixture.graph::getFameData);
                timed("fame/selected-60s-graph", () -> fixture.graph.trackingSnapshot(null, 60000));
                FameTrackingModel.TableCounts afterTable = fixture.table.trackingCounts();
                FameTrackingModel.HistoryCounts afterGraph = fixture.graph.trackingCounts();
                out("fame explicit-copy deltas: tableSnapshots=%d visitRows=%d graphSnapshots=%d sampleRefs=%d",
                    afterTable.snapshots - table.snapshots, afterTable.copiedVisits - table.copiedVisits,
                    afterGraph.snapshots - graph.snapshots, afterGraph.copiedSamples - graph.copiedSamples);
                FameTrackingModel.TableSnapshot state = fixture.table.trackingSnapshot();
                for (int id = 1; id <= CHARACTERS; id++) {
                    int first = ((id - 1) * samples + CHARACTERS - 1) / CHARACTERS;
                    int last = (id * samples + CHARACTERS - 1) / CHARACTERS - 1;
                    require(state.observed.get(id) == logicalTime(last, samples) - logicalTime(first, samples),
                        "Unchanged fame observations retain time, excluding other characters");
                }
            }
        } finally {
            edt(() -> { fixture.frame.dispose(); FameTableBridge.getInstance().setFameTablePanel(null); FameTableBridge.getInstance().setFameTrackerGUI(null); });
        }
    }

    private static final class FameFixture {
        FameTablePanel table;
        FameTrackerGUI graph;
        JFrame frame;
        int previousVisit = -1, saves, pendingFlagSamples, tableFlagSetSamples, graphFlagSetSamples;
        void create() {
            table = new FameTablePanel(null);
            graph = new FameTrackerGUI((session, completion) -> { saves++; completion.accept(true); });
            FameTableBridge.getInstance().setFameTablePanel(table);
            FameTableBridge.getInstance().setFameTrackerGUI(graph);
            frame = frame("Synthetic fame", 1, 2, table, graph);
        }
        void update(int i, int samples) {
            int visit = visit(i, samples, VISITS), id = character(i, samples);
            long time = logicalTime(i, samples), fame = fameValue(i, samples);
            if (visit != previousVisit) { table.onMapChange(map(visit), time); previousVisit = visit; }
            FameTableBridge.observeFame(id, fame, time, "Synthetic class " + id);
        }
        void samplePendingFlags() {
            pendingFlagSamples++;
            if (table.refreshCounts().pending != 0) tableFlagSetSamples++;
            if (graph.refreshCounts().pending != 0) graphFlagSetSamples++;
        }
        void checkRetention(int count, int total) {
            FameTrackingModel.TableCounts t = table.trackingCounts();
            FameTrackingModel.HistoryCounts h = graph.trackingCounts();
            require(t.characters == character(count - 1, total) && t.lastSamples == t.characters, "One latest table sample per observed character");
            require(t.closedVisits + t.openVisits == visit(count - 1, total, VISITS) + 1, "Fame visit boundaries preserved");
            long expectedSamples = (count + 9) / 10;
            for (int id = 1; id < CHARACTERS; id++) {
                int switchIndex = (id * total + CHARACTERS - 1) / CHARACTERS;
                if (switchIndex < count && switchIndex % 10 != 0) expectedSamples++;
            }
            require(h.samples == expectedSamples, "Capture history retains exactly the generated changes and switches");
            require(table.getCurrentFame(character(count - 1, total)).longValue() == fameValue(count - 1, total), "Tracking advances while hidden/visible");
        }
    }

    private static void discovery(int samples, boolean warmup) throws Exception {
        DiscoveryLog log = new DiscoveryLog(null);
        log.setSaving(false); log.setSampleMillis(0);
        DiscoveryFixture views = new DiscoveryFixture(log);
        edt(views::create);
        Packets packets = new Packets(samples, VISITS, (packet, type, now) -> log.observe(type.getIndex(), 96, packet, "decoded", 0));
        String prefix = warmup ? "warmup/discovery" : "discovery";
        try {
            if (!warmup) heap(prefix + "/baseline");
            produce(prefix + "/hidden", 0, samples / 2, warmup, packets::update, () -> { });
            require(log.activitySnapshotStats().views == 0 && log.activitySnapshotStats().full == 0 && log.diagnosticsSnapshotCopies() == 0,
                "Hidden discovery views do not copy history/diagnostics");
            produce(prefix + "/visible", samples / 2, samples, warmup, packets::update, () -> { }, () -> views.frame.setVisible(true));
            require(log.activitySnapshotStats().full == 0, "Live views never request full activity snapshots");
            if (!warmup) heap(prefix + "/retained-before-verification-reference");
            // Producer is stopped. This explicit full read is the oracle for each independent view.
            DiscoveryLog.Snapshot snapshot = log.snapshot();
            views.verifyFinal(snapshot);
            edt(() -> views.frame.setVisible(false));
            settleDiscovery(log);
            require(log.activitySnapshotStats().full == 1, "Only the explicit final-state reference copied full history");
            out("%s copy counts before timed probes (includes ONE explicit full verification reference; live full reads=0):", prefix);
            copies(log.activitySnapshotStats(), log.diagnosticsSnapshotCopies());
            require(snapshot.total == packets.frames && snapshot.observerErrors == 0, "All discovery frames observed without internal errors");
            require(snapshot.events.size() == Math.min(packets.frames, DiscoveryLog.EVENT_LIMIT) && snapshot.diskDropped == 0, "Bounded detailed diagnostic events");
            for (DiscoveryLog.Event event : snapshot.events) require(event.statChanges.size() <= DiscoveryLog.DELTA_LIMIT, "Per-event delta bound");
            out("%s frames=%d events=%d statRows=%d sampledOut=%d deltaOmitted=%d cacheEvictions=%d observerErrors=%d diskDrops=%d",
                prefix, snapshot.total, snapshot.events.size(), snapshot.stats.size(), snapshot.sampledOut, snapshot.deltaOmitted,
                snapshot.cacheEvictions, snapshot.observerErrors, snapshot.diskDropped);
            retention(prefix, snapshot.activity);
            if (!warmup) compareDiscovery(log);
        } finally { edt(() -> views.frame.dispose()); log.close(); }
    }

    private static final class DiscoveryFixture {
        final DiscoveryLog log;
        ActivityPanel runs, timeline, combat;
        LoggingGUI diagnostics;
        JFrame frame;
        DiscoveryFixture(DiscoveryLog log) { this.log = log; }
        void create() {
            runs = new ActivityPanel(log, ActivityPanel.Mode.RUNS);
            timeline = new ActivityPanel(log, ActivityPanel.Mode.TIMELINE);
            combat = new ActivityPanel(log, ActivityPanel.Mode.COMBAT);
            diagnostics = new LoggingGUI(log);
            frame = frame("Synthetic discovery", 2, 2, runs, timeline, combat, diagnostics);
        }
        void refresh() { runs.refresh(); timeline.refresh(); combat.refresh(); diagnostics.refresh(); }
        void verifyFinal(DiscoveryLog.Snapshot expected) throws Exception {
            ActivityJournal.State history = expected.activity;
            ActivityJournal.Visit latest = history.visits.get(history.visits.size() - 1);
            require(latest.id.endsWith(":" + VISITS) && FINAL_MAP.equals(latest.map) && latest.partyId != null,
                "Final reference contains the unique final visit and terminal roster marker");
            edt(this::refresh);
            await(() -> onEdt(() -> runsMatch(history, latest)), "Runs final visit rows and terminal detail marker");
            await(() -> onEdt(() -> choicesMatch(timeline, history, 1) && choicesMatch(combat, history, 0)),
                "Timeline and Combat final retained visit choices, including unique map marker");
            edt(() -> {
                selectKnownVisit(timeline, latest, 1);
                selectKnownVisit(combat, latest, 0);
                refresh();
            });
            ActivityJournal.Entry last = history.entries.get(history.entries.size() - 1);
            require(latest.id.equals(last.visitId) && "Party roster".equals(last.kind)
                && Objects.equals(last.values.get("partyId"), latest.partyId), "Terminal Timeline marker belongs to final visit");
            await(() -> onEdt(() -> timelineMatch(last, history)), "Timeline selected final visit and terminal event content");
            verifyCombat(latest, 0);
            await(() -> onEdt(() -> diagnostics.isShowing()
                && labelStarts(diagnostics, String.format(Locale.ROOT, "%,d frames", expected.total))), "Diagnostics final frame total");

            // Combat intentionally pins a selection; it must not be required to follow the latest visit.
            int retainedIndex = history.visits.size() / 2;
            ActivityJournal.Visit selected = history.visits.get(retainedIndex);
            int choiceIndex = history.visits.size() - 1 - retainedIndex;
            edt(() -> selectKnownVisit(combat, selected, choiceIndex));
            verifyCombat(selected, choiceIndex);
            out("discovery content catch-up: Runs/Timeline finalVisit=%s map=%s party=%d; Combat chart inspections match final reference for latest and selectedVisit=%s",
                latest.id, latest.map, latest.partyId, selected.id);
        }
        boolean runsMatch(ActivityJournal.State history, ActivityJournal.Visit latest) {
            JTable table = named(runs, "activity-table", JTable.class);
            if (!runs.isShowing() || table.getRowCount() != history.visits.size()) return false;
            for (int row = 0; row < table.getRowCount(); row++) {
                ActivityJournal.Visit visit = history.visits.get(history.visits.size() - 1 - row);
                Object[] expected = {ActivityPanel.time(visit.started), visit.map, Math.max(0, visit.lastSeen - visit.started) / 1000,
                    visit.exaltIncrease, visit.useRequests, visit.issues, visit.status};
                for (int column = 0; column < expected.length; column++) {
                    if (!Objects.equals(expected[column], table.getModel().getValueAt(row, column))) return false;
                }
            }
            int latestRow = table.convertRowIndexToView(0);
            table.setRowSelectionInterval(latestRow, latestRow);
            return detailMatches(runs, latest); // Numeric terminal marker rules out an earlier snapshot of this same visit.
        }
        boolean choicesMatch(ActivityPanel panel, ActivityJournal.State history, int offset) {
            JComboBox<?> picker = named(panel, "activity-visit", JComboBox.class);
            if (!panel.isShowing() || picker.getItemCount() != history.visits.size() + offset) return false;
            for (int i = 0; i < history.visits.size(); i++) {
                ActivityJournal.Visit visit = history.visits.get(history.visits.size() - 1 - i);
                if (!visitLabel(visit).equals(picker.getItemAt(i + offset).toString())) return false;
            }
            return true;
        }
        void selectKnownVisit(ActivityPanel panel, ActivityJournal.Visit visit, int index) {
            JComboBox<?> picker = named(panel, "activity-visit", JComboBox.class);
            picker.setSelectedIndex(-1); // Make a missing-ID no-op observable, even if labels/timestamps repeat.
            panel.selectVisit(visit.id);
            require(picker.getSelectedIndex() == index && visitLabel(visit).equals(String.valueOf(picker.getSelectedItem())),
                "Public selectVisit resolves exact retained ID " + visit.id);
        }
        boolean timelineMatch(ActivityJournal.Entry marker, ActivityJournal.State history) {
            JTable table = named(timeline, "activity-table", JTable.class);
            JComboBox<?> picker = named(timeline, "activity-visit", JComboBox.class);
            ActivityJournal.Visit latest = history.visits.get(history.visits.size() - 1);
            if (picker.getSelectedIndex() != 1 || !visitLabel(latest).equals(String.valueOf(picker.getSelectedItem()))) return false;
            long rows = history.entries.stream().filter(e -> marker.visitId.equals(e.visitId)).count();
            if (table.getRowCount() != rows || rows == 0) return false;
            Object[] expected = {ActivityPanel.time(marker.time), marker.map, marker.kind,
                "Party " + marker.values.get("partyId") + " · " + marker.values.get("memberCount") + " observed members", marker.detail};
            for (int column = 0; column < expected.length; column++) {
                if (!Objects.equals(expected[column], table.getModel().getValueAt(0, column))) return false;
            }
            return true;
        }
        void verifyCombat(ActivityJournal.Visit selected, int choiceIndex) throws Exception {
            CombatTimelineChart[] reference = new CombatTimelineChart[1];
            edt(() -> { reference[0] = new CombatTimelineChart(); reference[0].setVisit(selected); });
            await(() -> onEdt(() -> {
                JComboBox<?> picker = named(combat, "activity-visit", JComboBox.class);
                if (picker.getSelectedIndex() != choiceIndex || !visitLabel(selected).equals(String.valueOf(picker.getSelectedItem()))
                    || !detailMatches(combat, selected)) return false;
                CombatTimelineChart actual = find(combat, CombatTimelineChart.class);
                // Compare the chart's public inspection output, not a second read of the observer.
                inspect(actual, "first-sample"); inspect(reference[0], "first-sample");
                if (!actual.getInspectionSummary().equals(reference[0].getInspectionSummary())) return false;
                for (int i = 1; i < selected.resourceTimeline.size(); i++) {
                    inspect(actual, "next-sample"); inspect(reference[0], "next-sample");
                    if (!actual.getInspectionSummary().equals(reference[0].getInspectionSummary())) return false;
                }
                return true;
            }), "Combat detail and chart samples for selected retained ID " + selected.id);
        }
        static String visitLabel(ActivityJournal.Visit visit) { return ActivityPanel.time(visit.started) + " · " + visit.map; }
        static boolean detailMatches(ActivityPanel panel, ActivityJournal.Visit visit) {
            String detail = named(panel, "activity-detail", JTextArea.class).getText();
            String party = visit.partyId == null ? "not observed" : "party " + visit.partyId + ", " + visit.rosterSize + " observed members; identity links unverified";
            return detail.startsWith(visit.map + " · " + ActivityPanel.time(visit.started) + "\n" + visit.status)
                && detail.contains("\nParty roster: " + party + "\nProgress increase within this visit:");
        }
        static void inspect(CombatTimelineChart chart, String action) {
            chart.getActionMap().get(action).actionPerformed(new ActionEvent(chart, ActionEvent.ACTION_PERFORMED, action));
        }
    }

    private static void logicalActivity(int samples, boolean warmup) throws Exception {
        ActivityJournal journal = new ActivityJournal();
        Packets packets = new Packets(samples, LOGICAL_VISITS,
            (packet, type, now) -> journal.observe(packet, type, "decoded", now, Collections.emptyMap()));
        String prefix = warmup ? "warmup/logical-activity" : "logical-activity";
        if (!warmup) heap(prefix + "/baseline");
        produce(prefix, 0, samples, warmup, packets::update, () -> { });
        if (!warmup) heap(prefix + "/retained-after-workload");
        ActivityJournal.State state = journal.snapshot();
        retention(prefix, state);
        require(state.visits.size() == LOGICAL_VISITS, "Logical long visits retained");
        require(state.visits.get(state.visits.size() - 1).lastSeen == EPOCH + LOGICAL_MS, "Deterministic six-hour endpoint");
        if (!warmup) {
            ActivityJournal.SnapshotStats before = journal.snapshotStats();
            timed(prefix + "/full", journal::snapshot);
            for (ActivityJournal.View view : ActivityJournal.View.values()) {
                ActivityJournal.SnapshotStats a = journal.snapshotStats();
                timed(prefix + "/" + view, () -> journal.viewSnapshot(view, "", null));
                copyDelta(journal.snapshotStats(), a);
                narrowCopyBudget(view, journal.snapshotStats(), a);
                ActivityJournal.ViewSnapshot known = journal.viewSnapshot(view, "", null);
                ActivityJournal.SnapshotStats unchanged = journal.snapshotStats();
                timed(prefix + "/" + view + "-unchanged", () -> {
                    require(journal.viewSnapshot(view, "", known.revision) == null, "Unchanged logical view returns null"); return null;
                });
                require(journal.snapshotStats().views == unchanged.views, "Unchanged logical views copy nothing");
            }
            out("logical-activity explicit-copy totals:"); copyDelta(journal.snapshotStats(), before);
        }
    }

    @FunctionalInterface private interface Observer { void observe(Packet packet, PacketType type, long now); }

    private static final class Packets {
        final int samples, visits;
        final Observer observer;
        int previousVisit = -1;
        long frames;
        Packets(int samples, int visits, Observer observer) { this.samples = samples; this.visits = visits; this.observer = observer; }
        void send(Packet packet, PacketType type, long now) { observer.observe(packet, type, now); frames++; }
        void update(int i) {
            long now = logicalTime(i, samples);
            int visit = visit(i, samples, visits);
            if (visit != previousVisit) {
                MapInfoPacket map = new MapInfoPacket(); map.name = visit == visits - 1 ? FINAL_MAP : map(visit); map.displayName = map.name;
                send(map, PacketType.MAPINFO, now);
                CreateSuccessPacket created = new CreateSuccessPacket(); created.objectId = 42; created.charId = 1 + visit % CHARACTERS;
                send(created, PacketType.CREATE_SUCCESS, now); previousVisit = visit;
            }
            NewTickPacket tick = new NewTickPacket(); tick.tickId = i;
            tick.tickTime = i == 0 ? 0 : (int)(now - logicalTime(i - 1, samples)); tick.serverRealTimeMS = now - EPOCH;
            ObjectStatusData local = new ObjectStatusData(); local.objectId = 42;
            local.stats = new StatData[]{stat(1, 500 + i % 200), stat(4, 200 + i % 100), stat(29, i % 2 == 0 ? 0 : 2), stat(96, 0)};
            ObjectStatusData remote = new ObjectStatusData(); remote.objectId = 100 + i % 25000;
            remote.stats = new StatData[]{stat(1, 300 + i % 100)};
            tick.status = new ObjectStatusData[]{local, remote};
            send(tick, PacketType.NEWTICK, now);
            if (i == samples - 1) {
                IncomingPartyMemberInfoPacket marker = new IncomingPartyMemberInfoPacket();
                marker.partyId = FINAL_PARTY_BASE + samples; marker.partyPlayers = new PartyPlayerData[0];
                send(marker, PacketType.byClass(marker), now);
            }
        }
    }

    private static StatData stat(int id, int value) { StatData stat = new StatData(); stat.statTypeNum = id; stat.statValue = value; return stat; }
    private static int visit(int i, int samples, int visits) { return (int)((long)i * visits / samples); }
    private static int character(int i, int samples) { return 1 + visit(i, samples, CHARACTERS); }
    private static long fameValue(int i, int samples) { return 1000L * character(i, samples) + i / 10; }
    private static long logicalTime(int i, int samples) { return EPOCH + LOGICAL_MS * i / (samples - 1); }
    private static String map(int visit) { return visit % 2 == 0 ? "Ice Citadel" : "Ocean Trench"; }

    private static void retention(String name, ActivityJournal.State state) {
        long points = 0, slices = 0, omitted = 0, ticks = 0;
        int maxPoints = 0, maxSlices = 0;
        require(state.visits.size() <= ActivityJournal.RUN_LIMIT && state.entries.size() <= ActivityJournal.EVENT_LIMIT, "Activity retention caps");
        for (ActivityJournal.Visit visit : state.visits) {
            int p = visit.resourceTimeline.size(), s = visit.conditionTimeline.size();
            require(p <= MAX_TIMELINE_PER_VISIT && s <= MAX_TIMELINE_PER_VISIT, "Per-visit timeline caps");
            points += p; slices += s; omitted += visit.timelineOmitted; ticks += visit.ticks;
            maxPoints = Math.max(maxPoints, p); maxSlices = Math.max(maxSlices, s);
        }
        require(points <= MAX_TIMELINE_TOTAL && slices <= MAX_TIMELINE_TOTAL, "Global timeline caps");
        out("%s retained: visits=%d events=%d resourcePoints=%d conditionSlices=%d maxPerVisit=%d/%d timelineOmitted=%d ticksInRetainedVisits=%d",
            name, state.visits.size(), state.entries.size(), points, slices, maxPoints, maxSlices, omitted, ticks);
        out("%s observed saturation: resourceCapReached=%s conditionCapReached=%s (coverage report, not an assertion)",
            name, points == MAX_TIMELINE_TOTAL, slices == MAX_TIMELINE_TOTAL);
    }

    private static void compareDiscovery(DiscoveryLog log) {
        ActivityJournal.SnapshotStats before = log.activitySnapshotStats();
        timed("discovery/full", log::snapshot);
        copyDelta(log.activitySnapshotStats(), before);
        for (ActivityJournal.View view : ActivityJournal.View.values()) {
            before = log.activitySnapshotStats();
            timed("discovery/" + view, () -> log.activityView(view, "", null));
            copyDelta(log.activitySnapshotStats(), before);
            narrowCopyBudget(view, log.activitySnapshotStats(), before);
            DiscoveryLog.ActivitySnapshot known = log.activityView(view, "", null);
            long count = log.activitySnapshotStats().views;
            timed("discovery/" + view + "-unchanged", () -> {
                require(log.activityView(view, "", known.revision) == null, "Unchanged discovery view returns null"); return null;
            });
            require(log.activitySnapshotStats().views == count, "Unchanged activity view copy count");
        }
        long count = log.diagnosticsSnapshotCopies();
        long full = log.activitySnapshotStats().full;
        timed("discovery/diagnostics", () -> log.diagnosticsSnapshot(null));
        out("  diagnostics copied payloads=%d", log.diagnosticsSnapshotCopies() - count);
        DiscoveryLog.DiagnosticsSnapshot known = log.diagnosticsSnapshot(null);
        require(known.data.activity == null && log.activitySnapshotStats().full == full, "Diagnostics exclude activity history");
        count = log.diagnosticsSnapshotCopies();
        timed("discovery/diagnostics-unchanged", () -> {
            require(log.diagnosticsSnapshot(known.revision) == null, "Unchanged diagnostics return null"); return null;
        });
        require(log.diagnosticsSnapshotCopies() == count, "Unchanged diagnostics copy count");
    }

    private static void narrowCopyBudget(ActivityJournal.View view, ActivityJournal.SnapshotStats after, ActivityJournal.SnapshotStats before) {
        require(after.full == before.full, "View read never materializes full history");
        if (view != ActivityJournal.View.COMBAT) {
            require(after.resourcePoints == before.resourcePoints && after.conditionSlices == before.conditionSlices,
                "Runs/Timeline do not copy chart data");
        }
    }

    private static void settleDiscovery(DiscoveryLog log) throws Exception {
        // Public APIs have no worker-idle signal. Observe a quiet interval after hiding, with a deadline.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long views = log.activitySnapshotStats().views, diagnostics = log.diagnosticsSnapshotCopies();
            Thread.sleep(250); edt(() -> { });
            if (views == log.activitySnapshotStats().views && diagnostics == log.diagnosticsSnapshotCopies()) return;
            require(System.nanoTime() < deadline, "Hidden discovery copy counters settle");
        }
    }

    private static void copies(ActivityJournal.SnapshotStats s, long diagnostics) {
        out("  full=%d views=%d visits=%d entries=%d points=%d slices=%d pinnedRefs=%d copyOnWriteRefs=%d diagnostics=%d",
            s.full, s.views, s.visits, s.entries, s.resourcePoints, s.conditionSlices, s.pinnedReferences, s.copyOnWriteReferences, diagnostics);
    }
    private static void copyDelta(ActivityJournal.SnapshotStats a, ActivityJournal.SnapshotStats b) {
        out("  copy delta: full=%d views=%d visits=%d entries=%d points=%d slices=%d pinnedRefs=%d copyOnWriteRefs=%d",
            a.full-b.full, a.views-b.views, a.visits-b.visits, a.entries-b.entries, a.resourcePoints-b.resourcePoints,
            a.conditionSlices-b.conditionSlices, a.pinnedReferences-b.pinnedReferences, a.copyOnWriteReferences-b.copyOnWriteReferences);
    }

    private static void timed(String name, Supplier<?> operation) {
        final int repeats = 12;
        long allocated = ALLOCATION.bytes(Thread.currentThread().getId()), start = System.nanoTime();
        for (int i = 0; i < repeats; i++) blackhole = operation.get();
        long nanos = System.nanoTime() - start;
        long bytes = ALLOCATION.delta(Thread.currentThread().getId(), allocated);
        blackhole = null; // At most one result retained; no collection of benchmark snapshots.
        out("snapshot %s: calls=%d elapsed=%.3f ms mean=%.3f us/call mainAllocatedBytes=%d", name, repeats, millis(nanos), nanos / 1000.0 / repeats, bytes);
    }

    private static void produce(String name, int from, int to, boolean warmup, IntConsumer update, Runnable sampleFlags) throws Exception {
        produce(name, from, to, warmup, update, sampleFlags, null);
    }

    private static void produce(String name, int from, int to, boolean warmup, IntConsumer update, Runnable sampleFlags, Runnable show) throws Exception {
        Heartbeats beats = new Heartbeats();
        long main = Thread.currentThread().getId();
        long allocation = ALLOCATION.bytes(main), edtAllocation = ALLOCATION.bytes(edtId);
        long started = System.nanoTime(), active = 0;
        long pace = TimeUnit.MILLISECONDS.toNanos(warmup ? 200 : PHASE_MIN_MS);
        beats.start();
        try {
            if (show != null) edt(show);
            // Pacing is outside the measured producer batches; keeps probes concurrent with real work.
            for (int first = from; first < to; first += 256) {
                int end = Math.min(to, first + 256);
                long begin = System.nanoTime();
                for (int i = first; i < end; i++) update.accept(i);
                active += System.nanoTime() - begin;
                sampleFlags.run(); checkAsync();
                long due = started + pace * (end - from) / (to - from);
                while (System.nanoTime() < due) LockSupport.parkNanos(Math.min(due - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(5)));
            }
        } finally { beats.stop(); }
        long elapsed = System.nanoTime() - started;
        out("%s: updates=%d active=%.3f ms mean=%.3f us/update activeThroughput=%.0f/s wall=%.3f ms pacedThroughput=%.0f/s allocatedBytes main=%d EDT=%d",
            name, to-from, millis(active), active / 1000.0 / (to-from), (to-from) * 1e9 / Math.max(1, active), millis(elapsed),
            (to-from) * 1e9 / elapsed, ALLOCATION.delta(main, allocation), ALLOCATION.delta(edtId, edtAllocation));
        beats.report(name);
    }

    private static final class Heartbeats {
        final long[] delays = new long[HEARTBEAT_CAPACITY];
        final AtomicBoolean pending = new AtomicBoolean();
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "benchmark-heartbeat"));
        int size;
        volatile long skipped;
        void start() {
            scheduler.scheduleWithFixedDelay(() -> {
                if (!pending.compareAndSet(false, true)) { skipped++; return; }
                long sent = System.nanoTime();
                SwingUtilities.invokeLater(() -> {
                    try {
                        require(size < delays.length, "Heartbeat storage remains bounded");
                        delays[size++] = System.nanoTime() - sent;
                    } finally { pending.set(false); }
                });
            }, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        }
        void stop() throws Exception {
            scheduler.shutdownNow();
            require(scheduler.awaitTermination(10, TimeUnit.SECONDS), "Heartbeat scheduler terminates");
            edt(() -> { });
            require(!pending.get(), "Heartbeat callback drained");
        }
        void report(String name) {
            require(size > 0, "EDT made progress");
            Arrays.sort(delays, 0, size);
            double median = millis(delays[(int)Math.ceil(size * .5) - 1]);
            double p95 = millis(delays[(int)Math.ceil(size * .95) - 1]), max = millis(delays[size - 1]);
            out("%s EDT: n=%d median=%.3f ms p95=%.3f ms max=%.3f ms skippedProbeTicks=%d harnessOutstandingLimit=1 finalPending=0 p95Evidence=%s softLatencyBudget=%s",
                name, size, median, p95, max, skipped, size >= P95_MIN_SAMPLES ? "sufficient" : "INSUFFICIENT",
                p95 <= SOFT_P95_MS && max <= SOFT_MAX_MS ? "within" : "exceeded (report only)");
        }
    }

    private static final class Allocation {
        final com.sun.management.ThreadMXBean bean;
        final String status;
        Allocation() {
            com.sun.management.ThreadMXBean available = null;
            String reason = "unsupported (-1 means unavailable)";
            try {
                java.lang.management.ThreadMXBean candidate = ManagementFactory.getThreadMXBean();
                if (candidate instanceof com.sun.management.ThreadMXBean) {
                    available = (com.sun.management.ThreadMXBean)candidate;
                    if (!available.isThreadAllocatedMemorySupported()) available = null;
                    else {
                        if (!available.isThreadAllocatedMemoryEnabled()) available.setThreadAllocatedMemoryEnabled(true);
                        reason = "ThreadMXBean allocated bytes enabled (-1 means unavailable)";
                    }
                }
            } catch (RuntimeException | LinkageError error) { available = null; reason = "unavailable: " + error.getClass().getSimpleName(); }
            bean = available; status = reason;
        }
        long bytes(long id) { return bean == null ? -1 : bean.getThreadAllocatedBytes(id); }
        long delta(long id, long before) { long after = bytes(id); return before < 0 || after < before ? -1 : after - before; }
    }

    private static void heap(String name) throws Exception {
        edt(() -> { });
        long before = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.gc(); Thread.sleep(100);
        long after = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        out("heap %s: usedBeforeRequestedGC=%d usedAfterRequestedGC=%d bytes (observational; live fixtures + UI + JVM caches)", name, before, after);
    }

    private static JFrame frame(String title, int rows, int columns, Component... components) {
        JPanel content = new JPanel(new GridLayout(rows, columns, 8, 8));
        for (Component component : components) content.add(component);
        JFrame frame = new JFrame(title); frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setContentPane(content); frame.pack(); frame.setSize(1440, 900);
        return frame; // Displayable but hidden until the measured visibility transition.
    }
    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        T result = findMatching(root, type, name);
        require(result != null, "Missing public component " + name); return result;
    }
    private static <T extends Component> T find(Container root, Class<T> type) {
        T result = findMatching(root, type, null);
        require(result != null, "Missing component " + type.getSimpleName()); return result;
    }
    private static <T extends Component> T findMatching(Component component, Class<T> type, String name) {
        if (type.isInstance(component) && (name == null || name.equals(component.getName()))) return type.cast(component);
        if (component instanceof Container) for (Component child : ((Container)component).getComponents()) {
            T result = findMatching(child, type, name); if (result != null) return result;
        }
        return null;
    }
    private static boolean labelStarts(Component component, String text) {
        if (component instanceof JLabel && ((JLabel)component).getText() != null && ((JLabel)component).getText().startsWith(text)) return true;
        if (component instanceof Container) for (Component child : ((Container)component).getComponents()) if (labelStarts(child, text)) return true;
        return false;
    }
    private static void edt(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); return; }
        FutureTask<Void> task = new FutureTask<>(action, null);
        SwingUtilities.invokeLater(task); task.get(10, TimeUnit.SECONDS); checkAsync();
    }
    private static boolean onEdt(BooleanSupplier condition) {
        boolean[] result = {false};
        try { edt(() -> result[0] = condition.getAsBoolean()); }
        catch (Exception error) { throw new IllegalStateException(error); }
        return result[0];
    }
    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            require(System.nanoTime() < deadline, description + " within harness drain timeout");
            checkAsync(); Thread.sleep(15);
        }
    }
    private static Thread daemon(Runnable action, String name) { Thread thread = new Thread(action, name); thread.setDaemon(true); return thread; }
    private static void checkAsync() { Throwable error = ASYNC_FAILURE.get(); if (error != null) throw new AssertionError("Asynchronous failure", error); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static double millis(long nanos) { return nanos / 1000000.0; }
    private static void out(String format, Object... args) { System.out.printf(Locale.ROOT, format + "%n", args); }
}
