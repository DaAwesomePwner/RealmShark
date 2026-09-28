package tomato.history.encounter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.history.encounter.CombatSettings.*;

public class CombatSettingsTest {
    private static Values read(String keep, String days, String summaries) {
        Map<String, String> store = new HashMap<>();
        if (keep != null) store.put(KEEP_FULL_DETAIL, keep);
        if (days != null) store.put(FULL_DETAIL_DAYS, days);
        if (summaries != null) store.put(SUMMARY_RETENTION, summaries);
        return CombatSettings.read(store::get);
    }

    @Test public void absentKeysGiveTheDefaults() {
        assertEquals("combat.keepFullDetail", KEEP_FULL_DETAIL);
        assertEquals("combat.fullDetailDays", FULL_DETAIL_DAYS);
        assertEquals("combat.summaryRetention", SUMMARY_RETENTION);
        Values values = read(null, null, null);
        assertEquals(new Values(false, 30, null), values);
        assertEquals(DEFAULTS, values);
        assertFalse("Full detail is off by default", values.keepFullDetail());
        assertEquals(30, values.fullDetailDays());
        assertNull("Summaries are kept forever by default", values.summaryDays());
        assertEquals(List.of("7", "30", "90", "365"), FULL_DETAIL_DAYS_VALUES);
        assertEquals(List.of("all", "365", "90"), SUMMARY_RETENTION_VALUES);
    }

    @Test public void everyOfferedValueParses() {
        assertTrue(read("true", null, null).keepFullDetail());
        assertFalse(read("false", null, null).keepFullDetail());
        assertEquals(7, read(null, "7", null).fullDetailDays());
        assertEquals(30, read(null, "30", null).fullDetailDays());
        assertEquals(90, read(null, "90", null).fullDetailDays());
        assertEquals(365, read(null, "365", null).fullDetailDays());
        assertNull(read(null, null, "all").summaryDays());
        assertEquals(Integer.valueOf(365), read(null, null, "365").summaryDays());
        assertEquals(Integer.valueOf(90), read(null, null, "90").summaryDays());
        assertEquals(new Values(true, 90, 365), read("true", "90", "365"));
    }

    @Test public void anythingInvalidIsTheDefaultForThatKeyOnly() {
        for (String keep : new String[] {"", "TRUE", "True", " true", "true ", "yes", "1", "on"})
            assertFalse("keepFullDetail '" + keep + "'", read(keep, "90", "90").keepFullDetail());
        for (String days : new String[] {"", "45", "030", " 30", "30 ", "+30", "-7", "0", "abc", "365.0", "2147483648", "1 year"})
            assertEquals("fullDetailDays '" + days + "'", 30, read("true", days, "90").fullDetailDays());
        for (String summaries : new String[] {"", "ALL", "forever", "Forever", "180", "30", " 90", "90.0", "-1", "0"})
            assertNull("summaryRetention '" + summaries + "'", read("true", "7", summaries).summaryDays());
        assertEquals("A bad value leaves the other keys as saved", new Values(true, 7, 90), read("true", "7", "90"));
        assertEquals(new Values(true, 30, 90), read("true", "31", "90"));
        assertEquals(new Values(false, 7, 90), read("maybe", "7", "90"));
    }

    @Test public void eachKeyIsReadOnceAndNullReadsAreDefaults() {
        List<String> asked = new ArrayList<>();
        Values values = CombatSettings.read(key -> { asked.add(key); return null; });
        assertEquals(DEFAULTS, values);
        assertEquals(List.of(KEEP_FULL_DETAIL, FULL_DETAIL_DAYS, SUMMARY_RETENTION), asked);
    }

    @Test public void currentReadsTheApplicationPreferences() {
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : new String[] {KEEP_FULL_DETAIL, FULL_DETAIL_DAYS, SUMMARY_RETENTION}) saved.put(key, PropertiesManager.getProperty(key));
        try {
            PropertiesManager.setProperties(KEEP_FULL_DETAIL, "true");
            PropertiesManager.setProperties(FULL_DETAIL_DAYS, "7");
            PropertiesManager.setProperties(SUMMARY_RETENTION, "365");
            assertEquals(new Values(true, 7, 365), current());
            PropertiesManager.setProperties(FULL_DETAIL_DAYS, "8");
            assertEquals(new Values(true, 30, 365), current());
        } finally {
            // Keys cannot be removed; "" reads as absent (the default).
            saved.forEach((key, value) -> PropertiesManager.setProperties(key, value == null ? "" : value));
        }
    }

    @Test public void changeListenersRunAfterEachChangeUntilRemovedAndOneFailureDoesNotStopTheOthers() throws Exception {
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        Runnable failing = () -> { throw new IllegalStateException("synthetic listener failure"); };
        Runnable counting = first::incrementAndGet, other = second::incrementAndGet;
        onChange(failing);
        onChange(counting);
        onChange(other);
        try {
            changed();
            assertEquals(1, first.get());
            assertEquals("A failing listener does not stop later listeners", 1, second.get());
            removeOnChange(counting);
            changed();
            assertEquals("Removed listeners are not called", 1, first.get());
            assertEquals(2, second.get());
            // Listeners may be added from any thread, for example the combat worker.
            Thread worker = new Thread(() -> onChange(counting), "synthetic combat worker");
            worker.start();
            worker.join();
            changed();
            assertEquals(2, first.get());
            assertEquals(3, second.get());
        } finally {
            removeOnChange(failing);
            removeOnChange(counting);
            removeOnChange(other);
        }
        changed();
        assertEquals(2, first.get());
        assertEquals(3, second.get());
    }
}
