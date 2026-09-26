package tomato.gui.dps;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.*;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.activity.ActivityRouteTarget;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.SessionStore;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import util.PreferencesStore;

import javax.swing.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;

/** COMBAT-3 link status/actions and COMBAT-4 paged event explorer over synthetic encounters; no capture. */
public class DpsInvestigationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final int[] page = {0};

    @After public void uninstall() throws Exception { edt(() -> { Navigator.install(null); return null; }); }

    static Entity actor(TomatoData data, int id, String name) { return new Entity(data, id, 0) { @Override public String name() { return name; } }; }
    static void gear(Entity entity, int weapon) {
        int[] items = {weapon, 301, 302, 303};
        StatType[] types = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};
        for (int i = 0; i < 4; i++) { StatData stat = new StatData(); stat.statValue = items[i]; entity.stat.set(types[i], stat); }
    }

    @Test public void eventOneOfTwelveHundredIsReachableAndFiltersPrecedePaging() throws Exception {
        TomatoData data = new TomatoData(); Entity self = actor(data, 7, "Self"); gear(self, 100);
        List<Damage> hits = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            Projectile projectile = new Projectile(10 + i);
            projectile.setSource(i % 3 == 0 ? DamageSource.ABILITY : DamageSource.WEAPON, i % 3 == 0 ? 0 : 100);
            Damage hit = new Damage(self, projectile, 1000 + i * 10L);
            hit.oryx3GuardDmg = i == 600;
            hits.add(hit);
        }
        Collections.shuffle(hits, new Random(4)); // Numbering is chronological, not insertion order.
        DamageEventExplorer explorer = edt(() -> new DamageEventExplorer("Self", hits, Collections.emptyList(), false, 1000, "fixture"));
        edt(() -> {
            assertTrue(explorer.statusText(), explorer.statusText().contains("Page 1 of 6 · " + n(1200) + " matching of " + n(1200)));
            assertTrue(explorer.goToEvent(1200)); assertEquals(5, explorer.pageIndex());
            assertTrue("Event 1 of 1,200 is reachable, unlike the latest-500 text", explorer.goToEvent(1));
            assertEquals(0, explorer.pageIndex());
            JTable table = explorer.table();
            assertEquals(1, table.getValueAt(table.getSelectedRow(), 0));
            assertEquals(10, table.getValueAt(table.getSelectedRow(), 2));
            assertTrue(explorer.detailText().contains("Event 1 · Outgoing · 10 damage"));

            DamageEvents.Filter amount = new DamageEvents.Filter(); amount.minimum = 500; amount.maximum = 509;
            explorer.apply(amount); assertEquals(10, table.getRowCount());
            assertFalse("A filtered-out event is explained, not silently shown", explorer.goToEvent(1));
            DamageEvents.Filter time = new DamageEvents.Filter(); time.fromMillis = 0L; time.untilMillis = 50L; // half-open
            explorer.apply(time); assertEquals(5, table.getRowCount());
            DamageEvents.Filter source = new DamageEvents.Filter(); source.sources = EnumSet.of(DamageSource.ABILITY);
            explorer.apply(source); assertTrue(explorer.statusText().contains("400 matching of " + n(1200)));
            DamageEvents.Filter item = new DamageEvents.Filter(); item.text = "item #100";
            explorer.apply(item); assertTrue(explorer.statusText().contains("800 matching"));
            DamageEvents.Filter flag = new DamageEvents.Filter(); flag.flags = EnumSet.of(DamageEvents.Flag.ORYX_GUARD);
            explorer.apply(flag); assertEquals(1, table.getRowCount()); assertEquals(601, table.getValueAt(0, 0));
            return null;
        });
    }

    @Test public void outgoingWeaponSwapMatchesItsEventAndIncomingVictimGearIsNotCaptured() throws Exception {
        TomatoData data = new TomatoData(); Entity self = actor(data, 7, "Self"), boss = actor(data, 90, "Boss");
        gear(self, 100); Damage before = new Damage(self, new Projectile(50), 1000);
        gear(self, 200); Damage after = new Damage(self, new Projectile(60), 2000);
        Damage taken = new Damage(boss, 1500, 40);
        List<DamageEvents.Event> outgoing = DamageEvents.of(Arrays.asList(after, before), false, 1000);
        String first = DamageEvents.loadout(outgoing.get(0), "Self"), second = DamageEvents.loadout(outgoing.get(1), "Self");
        String retainedFirst = first.substring(first.indexOf("retained on this event"), first.indexOf("Last recorded gear"));
        assertTrue(retainedFirst, retainedFirst.contains("Weapon: Item #100"));
        assertTrue("Last-recorded gear is kept separate", first.substring(first.indexOf("Last recorded gear")).contains("Weapon: Item #200"));
        assertTrue(second.substring(0, second.indexOf("Last recorded gear")).contains("Weapon: Item #200"));
        String incoming = DamageEvents.loadout(DamageEvents.of(Collections.singletonList(taken), true, 1000).get(0), "Self");
        assertTrue(incoming, incoming.contains("Attacker: Boss"));
        assertTrue(incoming, incoming.contains("Victim (Self) equipment at this hit: Not captured"));
        assertFalse("Victim gear is never inferred from the local player's gear", incoming.contains("Item #"));
        Damage unknown = new Damage(null, 1600, 5); unknown.ownerInvntory = null;
        assertTrue(DamageEvents.loadout(DamageEvents.of(Collections.singletonList(unknown), false, Long.MAX_VALUE).get(0), "Self").contains("Not captured for this event"));
    }

    @Test public void meterKeepsDetailsAndSourceBreakdownWhileExplorerCoversEveryHit() throws Exception {
        TomatoData data = new TomatoData(); Entity self = actor(data, 7, "Self");
        Entity enemy = new Entity(data, 50, 0); StatData hp = new StatData(); hp.statValue = 100000; enemy.stat.set(StatType.MAX_HP_STAT, hp);
        for (int i = 0; i < 700; i++) enemy.genericDamageHit(self, new Projectile(1), 1000 + i);
        enemy.updateDamageTaken(1000); enemy.updateDamageTaken(1699);
        Filter.disable();
        MeterDpsGUI meter = edt(MeterDpsGUI::new);
        edt(() -> {
            meter.setContext(this, self);
            meter.renderData(new MapInfoPacket(), Collections.singletonList(enemy), new ArrayList<>(), 0, false);
            JTable table = tomato.gui.activity.ActivityArchiveUiTest.named(meter, JTable.class, "dps-player-table");
            assertEquals("rows " + table.getModel().getRowCount() + " enemy hits " + enemy.getDamageList().size(), 1, table.getRowCount());
            table.setRowSelectionInterval(0, 0);
            assertTrue(tomato.gui.activity.ActivityArchiveUiTest.named(meter, JButton.class, "dps-explore-events").isEnabled());
            JTextArea details = findDetails(meter);
            assertTrue(details.getText().contains("Showing latest 500 of 700 hits"));
            assertTrue(details.getText().contains("Damage by source"));
            CombatMeterData.Row row = meterRow(meter);
            DamageEventExplorer explorer = meter.explorer(row);
            assertTrue(explorer.statusText(), explorer.statusText().contains("700 matching of 700"));
            assertTrue(explorer.goToEvent(1));
            return null;
        });
    }
    private static JTextArea findDetails(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof JTextArea && ((JTextArea) c).getText().contains("Damage:")) return (JTextArea) c;
            if (c instanceof java.awt.Container) { JTextArea found = findDetails((java.awt.Container) c); if (found != null) return found; }
        }
        return null;
    }
    private static CombatMeterData.Row meterRow(MeterDpsGUI meter) throws Exception {
        java.lang.reflect.Field field = MeterDpsGUI.class.getDeclaredField("visible"); field.setAccessible(true);
        @SuppressWarnings("unchecked") List<CombatMeterData.Row> rows = (List<CombatMeterData.Row>) field.get(meter); return rows.get(0);
    }

    @Test public void linkedUnlinkedAndLegacyEncountersOfferOnlyVerifiedExactHandoffs() throws Exception {
        Path scratch = temp.newFolder().toPath();
        PreferencesStore preferences = new PreferencesStore(temp.getRoot().toPath().resolve("dps.properties")); preferences.preload();
        ViewStateStore states = ViewStateStore.preferences(preferences);
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            ActivityJournal.Visit a = visit("journal:a", 10_000), b = visit("journal:b", 80_000);
            store.put("runs", a.id, a); store.put("runs", b.id, b); store.flush();
            TomatoData data = new TomatoData();
            DpsData first = encounter(data, new EncounterContext(new VisitRef(store.currentId(), a.id), 7, 10_000));
            DpsData second = encounter(data, new EncounterContext(new VisitRef(store.currentId(), b.id), 7, 80_000));
            DpsData unlinked = encounter(data, new EncounterContext(null, 7, 90_000));
            DpsData legacy = encounter(data, null);
            data.dpsData.addAll(Arrays.asList(first, second, unlinked, legacy));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> resources =
                edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.COMBAT, scratch, states));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs =
                edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.RUNS, scratch, states));
            DpsGUI dps = edt(() -> new DpsGUI(data, DiscoveryLog.historyView(new ActivityJournal.State()), resources));
            try {
                ShellNavigator navigator = edt(() -> {
                    ShellNavigator created = new ShellNavigator(() -> page[0], value -> page[0] = value, WorkspaceShell::pageOf, 20);
                    Navigator.install(created);
                    created.register(ActivityRouteTarget.of(Destination.RUNS, runs));
                    created.register(dps.resourcesRouteTarget()); created.register(dps.encounterRouteTarget());
                    page[0] = 7; return created;
                });
                edt(() -> {
                    assertTrue(dps.showEncounter(entry(dps, second)));
                    assertEquals(EncounterLink.State.LINKED, dps.shownLink().state);
                    assertTrue(button(dps, "dps-open-run").isEnabled()); assertTrue(button(dps, "dps-open-resources").isEnabled());
                    JButton timeline = button(dps, "dps-open-timeline");
                    assertFalse("No Timeline target registered: explained, not offered", timeline.isEnabled());
                    assertTrue(text(dps).contains("Open Timeline unavailable"));
                    button(dps, "dps-open-run").doClick(); return null;
                });
                assertEquals(10, page[0]);
                assertEquals("The second same-name encounter opens only its own visit", b.id, edt(() -> runs.state().query.facets().visitId));
                await(() -> !runs.loading() && runs.displayedPage() != null && runs.displayedPage().matches == 1);
                assertEquals(b.id, edt(() -> runs.displayedPage().rows.get(0).value.visitId));
                assertTrue(edt(navigator::back)); assertEquals(7, page[0]);
                edt(() -> { button(dps, "dps-open-resources").doClick(); return null; });
                assertEquals(7, page[0]);
                assertSame(resources, edt(() -> dps.combatTabs().getSelectedComponent()));
                await(() -> !resources.loading() && resources.displayedPage() != null && resources.displayedPage().matches == 1);
                assertEquals(b.id, edt(() -> resources.displayedPage().rows.get(0).value.visitId));
                assertTrue(edt(navigator::back));
                assertEquals("Back restores the damage tab", 0, edt(() -> dps.combatTabs().getSelectedIndex()).intValue());
                edt(() -> {
                    assertTrue(dps.showEncounter(entry(dps, unlinked)));
                    assertEquals(EncounterLink.State.UNLINKED, dps.shownLink().state);
                    for (String name : new String[]{"dps-open-run", "dps-open-timeline", "dps-open-resources"}) assertFalse(name, button(dps, name).isEnabled());
                    assertTrue(text(dps).contains("Unlinked"));
                    assertTrue(dps.showEncounter(entry(dps, legacy)));
                    assertEquals(EncounterLink.State.LEGACY, dps.shownLink().state);
                    assertTrue(text(dps).contains("Legacy recording"));
                    assertFalse(button(dps, "dps-open-run").isEnabled());
                    dps.setIndex(-1);
                    assertEquals(EncounterLink.State.LIVE, dps.shownLink().state);
                    return null;
                });
            } finally { edt(() -> { resources.close(); runs.close(); return null; }); preferences.shutdown(5, TimeUnit.SECONDS, m -> {}); }
        }
    }
    static ActivityJournal.Visit visit(String id, long start) {
        ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = id; v.map = "Lost Halls"; v.started = start; v.lastSeen = v.ended = start + 50_000; return v;
    }
    static DpsData encounter(TomatoData data, EncounterContext context) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Lost Halls";
        Entity self = actor(data, 7, "Self"); self.objectType = 768;
        Entity enemy = new Entity(data, 50, 0); StatData hp = new StatData(); hp.statValue = 1000; enemy.stat.set(StatType.MAX_HP_STAT, hp);
        enemy.genericDamageHit(self, new Projectile(25), 1000); enemy.updateDamageTaken(1000); enemy.updateDamageTaken(2000);
        HashMap<Integer, Entity> hits = new HashMap<>(); hits.put(enemy.id, enemy);
        return context == null ? new DpsData(map, hits, new ArrayList<>(), 1000, 1000, null)
            : new DpsData(map, hits, new ArrayList<>(), 1000, 1000, null, self, context);
    }
    static String n(long value) { return tomato.gui.modern.DisplayFormat.formatInteger(value); }
    static String entry(DpsGUI dps, DpsData data) { return dps.encounters().find(data).id; }
    static JButton button(DpsGUI dps, String name) { return tomato.gui.activity.ActivityArchiveUiTest.named(dps, JButton.class, name); }
    static String text(DpsGUI dps) { return tomato.gui.activity.ActivityArchiveUiTest.named(dps, JTextArea.class, "dps-encounter-link").getText(); }
}
