package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

import static org.junit.Assert.*;

public class HistoricalDpsFilterTest {
    private static final Set<String> ALL = names("Alice", "Clare", "Bob", "Dan");

    @Test public void savedRelativeSelectionIsIdenticalAcrossModesWhileLiveCharacterChanges() throws Exception {
        onEdt(() -> {
            for (boolean persisted : new boolean[]{false, true}) {
                TomatoData data = new TomatoData();
                DpsData saved = encounter(true, persisted); data.dpsData.add(saved);
                data.player = player(50, 775, "Live", "Other", true);
                DpsGUI view = new DpsGUI(data); view.setIndex(0);
                for (boolean byGuild : new boolean[]{false, true}) {
                    preset(1, !byGuild, byGuild);
                    Set<String> expected = byGuild ? names("Alice", "Bob") : names("Alice", "Clare");
                    assertEveryMode(view, expected, Collections.emptySet(), false);
                    // Change both the mutable capture player and the published live snapshot.
                    data.player.objectType = 768;
                    data.player.stat.get(StatType.GUILD_NAME_STAT).stringStatValue = "Historic";
                    DpsGUI.updateMapPacket(data);
                    assertEveryMode(view, expected, Collections.emptySet(), false);
                    data.player = player(51, 775, "Next live", "Other", true);
                    DpsGUI.updateMapPacket(data);
                    assertEveryMode(view, expected, Collections.emptySet(), false);
                    assertEquals(0, view.getIndex());
                    assertSame(saved.getLocalPlayerContext(), field(field(view, "displayMeter"), "playerContext"));
                    assertSame(saved.getLocalPlayerContext(), field(field(view, "displayString"), "playerContext"));
                    assertSame(saved.getLocalPlayerContext(), field(field(view, "displayIcon"), "playerContext"));
                }
            }
        });
    }

    @Test public void missingHistoricalContextExplainsUnavailableRelativeFiltersAndRetainsExplicitPredicates() throws Exception {
        onEdt(() -> {
            TomatoData data = new TomatoData(); data.dpsData.add(encounter(false, false));
            data.player = player(50, 775, "Live", "Other", true);
            DpsGUI view = new DpsGUI(data); view.setIndex(0);
            preset(1, true, true);
            assertEveryMode(view, ALL, Collections.emptySet(), true);
            Filter.filterNames.add("dan");
            assertEveryMode(view, names("Dan"), Collections.emptySet(), true);
            Filter.filterNames.clear(); Filter.filterGuilds.add("historic");
            assertEveryMode(view, names("Alice", "Bob"), Collections.emptySet(), true);
            Filter.filterGuilds.clear(); Filter.filterClasses.add(768);
            assertEveryMode(view, names("Alice", "Clare"), Collections.emptySet(), true);
        });
    }

    @Test public void highlightRetainsRowsAndUsesHistoricalOrNamedMatchesAcrossModes() throws Exception {
        onEdt(() -> {
            TomatoData data = new TomatoData();
            data.dpsData.add(encounter(true, true)); data.dpsData.add(encounter(false, false));
            data.player = player(50, 775, "Live", "Other", true);
            DpsGUI view = new DpsGUI(data); view.setIndex(0);
            preset(2, true, false);
            assertEveryMode(view, ALL, names("Alice", "Clare"), false);
            preset(2, false, true);
            assertEveryMode(view, ALL, names("Alice", "Bob"), false);
            view.setIndex(1);
            assertEveryMode(view, ALL, Collections.emptySet(), true);
            Filter.filterNames.add("dan");
            assertEveryMode(view, ALL, names("Dan"), true);
        });
    }

    @Test public void missingGuildDoesNotDisableKnownClassAndExplainsObservedNoGuild() throws Exception {
        Entity local = player(1, 768, "Local", null, true);
        try {
            preset(1, true, true);
            DpsData.LocalPlayerContext context = DpsData.LocalPlayerContext.capture(local);
            assertTrue(Filter.shouldFilter(context));
            assertEquals(1, Filter.filter(player(2, 768, "Peer", "Any", false), context));
            assertTrue(Filter.unavailableReason(context).startsWith("My Guild unavailable"));
            local.stat.set(StatType.GUILD_NAME_STAT, text(""));
            preset(1, false, true);
            context = DpsData.LocalPlayerContext.capture(local);
            assertFalse(Filter.shouldFilter(context));
            assertTrue(Filter.unavailableReason(context).contains("has no guild"));
        } finally { Filter.selectFilter(null); Filter.disable(); }
    }

    private static void assertEveryMode(DpsGUI view, Set<String> expected, Set<String> highlighted, boolean unavailable) {
        JComboBox<?> mode = (JComboBox<?>) field(view, "viewMode");
        for (int style = 0; style < 3; style++) {
            DpsDisplayOptions.equipmentOption = style == 2 ? 3 : 0;
            mode.setSelectedIndex(style == 0 ? 0 : 1); DpsGUI.update();
            assertEquals("Mode " + style, expected, visibleNames(view, style));
            assertEquals("Highlight mode " + style, highlighted, highlightedNames(view, style));
            JTextArea notice = (JTextArea) field(view, "filterNotice");
            assertEquals(unavailable, notice.isVisible());
            if (unavailable) assertTrue(notice.getText().contains("unavailable"));
        }
    }

    private static Set<String> visibleNames(DpsGUI view, int style) {
        Set<String> result = new HashSet<>();
        if (style == 0) {
            JTable table = (JTable) field(field(view, "displayMeter"), "table");
            for (int row = 0; row < table.getRowCount(); row++) result.add((String) table.getValueAt(row, 0));
        } else if (style == 1) {
            String report = ((JTextArea) field(field(view, "displayString"), "textAreaDPS")).getText();
            for (String name : ALL) if (report.contains(name)) result.add(name);
        } else {
            for (JLabel label : labels((Container) field(field(view, "displayIcon"), "charPanel")))
                if (ALL.contains(label.getText())) result.add(label.getText());
        }
        return result;
    }

    private static Set<String> highlightedNames(DpsGUI view, int style) {
        Set<String> result = new HashSet<>();
        if (style == 0) {
            JTable table = (JTable) field(field(view, "displayMeter"), "table");
            for (int row = 0; row < table.getRowCount(); row++) {
                JLabel cell = (JLabel) table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                if (cell.getText().endsWith(" ★")) result.add((String) table.getValueAt(row, 0));
            }
        } else if (style == 1) {
            String report = ((JTextArea) field(field(view, "displayString"), "textAreaDPS")).getText();
            for (String line : report.split("\n")) if (line.startsWith(">>>"))
                for (String name : ALL) if (line.contains(name)) result.add(name);
        } else {
            for (JLabel label : labels((Container) field(field(view, "displayIcon"), "charPanel"))) {
                if (!ALL.contains(label.getText())) continue;
                // The name's wrapper and the indicator's wrapper share one player row.
                for (JLabel sibling : labels(label.getParent().getParent()))
                    if (sibling.getText() != null && sibling.getText().startsWith(">>")) result.add(label.getText());
            }
        }
        return result;
    }

    private static List<JLabel> labels(Container parent) {
        List<JLabel> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel) result.add((JLabel) child);
            else if (child instanceof Container) result.addAll(labels((Container) child));
        }
        return result;
    }

    private static DpsData encounter(boolean localMarked, boolean persisted) {
        Entity local = player(1, 768, "Alice", "Historic", localMarked);
        Entity target = new Entity(null, 99, 0); target.objectType = 100;
        StatData hp = new StatData(); hp.statValue = 10000; target.stat.set(StatType.MAX_HP_STAT, hp);
        Entity[] players = {local, player(2, 768, "Clare", "Other", false),
            player(3, 775, "Bob", "Historic", false), player(4, 775, "Dan", "Other", false)};
        for (Entity player : players) target.genericDamageHit(player, new Projectile(100 * player.id), 1000 + player.id);
        target.updateDamageTaken(1000); target.updateDamageTaken(2000);
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Historical encounter";
        HashMap<Integer, Entity> hits = new HashMap<>(); hits.put(target.id, target);
        return persisted ? new DpsData(map, hits, new ArrayList<>(), 3000, 0, null, local)
            : new DpsData(map, hits, new ArrayList<>(), 3000, 0, null);
    }

    private static Entity player(int id, int type, String name, String guild, boolean user) {
        // The test working directory has no asset catalog; keep display names independent of it.
        Entity entity = new Entity(null, id, 0) { @Override public String name() { return getStatName(); } };
        entity.objectType = type;
        entity.stat.set(StatType.NAME_STAT, text(name));
        if (guild != null) entity.stat.set(StatType.GUILD_NAME_STAT, text(guild));
        if (user) entity.setUser(1);
        return entity;
    }
    private static StatData text(String text) { StatData stat = new StatData(); stat.stringStatValue = text; return stat; }
    private static Set<String> names(String... names) { return new HashSet<>(Arrays.asList(names)); }
    private static void preset(int mode, boolean myClass, boolean myGuild) {
        Filter.selectFilter(null); Filter.filter = mode; Filter.myClassFilter = myClass; Filter.myGuildFilter = myGuild;
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void onEdt(Runnable assertions) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int equipment = DpsDisplayOptions.equipmentOption;
            int sort = DpsDisplayOptions.sortOption;
            boolean showMe = DpsDisplayOptions.showMe;
            try { Filter.disable(); DpsDisplayOptions.showMe = false; DpsDisplayOptions.sortOption = 0; assertions.run(); }
            finally {
                DpsDisplayOptions.equipmentOption = equipment; DpsDisplayOptions.showMe = showMe;
                DpsDisplayOptions.sortOption = sort;
                Filter.selectFilter(null); Filter.disable();
            }
        });
    }
}
