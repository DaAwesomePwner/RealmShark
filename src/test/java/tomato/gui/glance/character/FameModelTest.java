package tomato.gui.glance.character;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.FameFixtures.*;

/** Sheet › Fame's tiles from synthetic readings: live, saved or unknown fame; Home's per-hour rule per session; recorded gain. */
public class FameModelTest {
    private static final FameHistory.Series NONE = series(0, 0);

    private static String date(long time) { return DisplayFormat.DATE.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())); }
    private static FameModel model(FameHistory.Series series) { return FameModel.build(KEY, series, record(1_234L, T1), null); }

    @Test public void fameIsLiveOnlyForThisCharacterInGameElseSavedAsStaleElseUnknown() {
        FameModel.Current live = FameModel.build(KEY, NONE, record(1_234L, T1 - HOUR), live(ACCOUNT, 7, 1_500L)).current();
        assertTrue(live.live());
        assertEquals(DisplayValue.State.KNOWN, live.value().state); assertEquals("1,500", live.value().text());
        assertEquals("Live character stats", live.value().detail);
        for (FameModel.Current saved : List.of(
                FameModel.build(KEY, NONE, record(1_234L, T1 - HOUR), live(OTHER, 7, 9_000L)).current(),   // another account's #7
                FameModel.build(KEY, NONE, record(1_234L, T1 - HOUR), live(ACCOUNT, 8, 9_000L)).current(), // this account's #8
                FameModel.build(KEY, NONE, record(1_234L, T1 - HOUR), live(ACCOUNT, 7, null)).current(),   // live fame not captured
                FameModel.build(KEY, NONE, record(1_234L, T1 - HOUR), null).current())) {
            assertFalse(saved.live());
            assertEquals(DisplayValue.State.STALE, saved.value().state); assertEquals("1,234", saved.value().text());
            assertEquals("The tab words its age from this time", T1 - HOUR, saved.savedAt());
            assertTrue(saved.value().detail, saved.value().detail.startsWith("Saved in the character journal, last seen "));
        }
        FameModel.Current unknown = FameModel.build(KEY, NONE, record(null, T1), live(OTHER, 7, 9_000L)).current();
        assertEquals(DisplayValue.State.UNKNOWN, unknown.value().state); assertEquals(DisplayFormat.UNAVAILABLE, unknown.value().text());
        assertEquals("Unknown is never zero; its reason is the tooltip", SheetModelBuilder.FAME_UNKNOWN, unknown.value().detail);
        assertEquals(DisplayValue.State.UNKNOWN, FameModel.build(KEY, NONE, null, null).current().value().state);
        FameModel.Current zero = FameModel.build(KEY, NONE, null, live(ACCOUNT, 7, 0L)).current();
        assertTrue(zero.live()); assertEquals("A new character's live 0 is a real zero", DisplayValue.State.ZERO, zero.value().state);
        assertEquals("A record of another key is not this character's",
            DisplayValue.State.UNKNOWN, FameModel.build(OTHER + ":7", NONE, record(1_234L, T1), null).current().value().state);
    }

    @Test public void fameAnHourNeedsTenMinutesOfReadingsInOneSession() {
        FameModel nine = model(series(0, 0, readings(FIRST, false, T0, 100, T0 + 9 * MINUTE, 160)));
        assertEquals(DisplayValue.State.UNKNOWN, nine.perHour().state);
        assertEquals(FameModel.RATE_UNKNOWN, nine.perHour().detail); assertNull(nine.perHourBasis());
        FameModel ten = model(series(0, 0, readings(FIRST, false, T0, 100, T0 + 10 * MINUTE, 160)));
        assertEquals("Sample fame is estimated from experience", DisplayValue.State.ESTIMATE, ten.perHour().state);
        assertTrue(ten.perHour().text(), ten.perHour().text().endsWith(" 360"));   // 60 fame over 10 minutes of readings
        assertEquals("session of " + date(T0), ten.perHourBasis());
        assertTrue(ten.perHour().detail, ten.perHour().detail.contains(FameModel.ESTIMATED));
        FameModel current = model(series(0, 0, readings("now", true, T1, 100, T1 + 20 * MINUTE, 200)));
        assertEquals("this session", current.perHourBasis());
        assertTrue(current.perHour().text().endsWith(" 300"));
    }

    @Test public void fameAnHourComesFromTheNewestSessionThatQualifiesAndIgnoresDecreases() {
        FameModel model = model(series(0, 0,
            readings(FIRST, false, T0, 100, T0 + 4 * MINUTE, 90, T0 + 10 * MINUTE, 150),   // +60 (the drop to 90 is ignored) in 10 min
            readings(SECOND, false, T1, 1_300, T1 + 9 * MINUTE, 1_400)));                   // the newest, but only 9 minutes
        assertTrue(model.perHour().text(), model.perHour().text().endsWith(" 360"));
        assertEquals("session of " + date(T0), model.perHourBasis());
        FameModel both = model(series(0, 0, readings(FIRST, false, T0, 100, T0 + 10 * MINUTE, 160), readings(SECOND, false, T1, 0, T1 + 30 * MINUTE, 1_000)));
        assertTrue("Each session divides by its own reading time", both.perHour().text().endsWith(" 2,000"));
        assertEquals("session of " + date(T1), both.perHourBasis());
    }

    @Test public void recordedGainAddsIncreasesWithinEachSession() {
        FameModel model = model(series(0, 0,
            readings(FIRST, false, T0, 100, T0 + 4 * MINUTE, 90, T0 + 10 * MINUTE, 150),
            readings(SECOND, false, T1, 1_300, T1 + 9 * MINUTE, 1_400)));
        assertEquals(DisplayValue.State.ESTIMATE, model.gained().state);
        assertTrue("+60 and +100; the 1,150 between the sessions was not recorded", model.gained().text().endsWith(" +160"));
        assertEquals("over 2 sessions", model.gainedBasis());
        assertTrue(model.gained().detail, model.gained().detail.contains(FameModel.ESTIMATED));
        FameModel one = model(series(0, 0, readings(FIRST, false, T0, 500)));
        assertTrue("One reading: a baseline, no gain", one.gained().text().endsWith(" 0"));
        assertEquals("over 1 session", one.gainedBasis());
        assertEquals(2, model.sessions().size());
    }

    @Test public void anEmptySeriesHasUnknownTilesAndKeepsItsCounts() {
        FameModel empty = FameModel.build(KEY, series(4, 1), record(1_234L, T1), null);
        assertTrue(empty.sessions().isEmpty());
        assertEquals(DisplayValue.State.UNKNOWN, empty.gained().state); assertNull(empty.gainedBasis());
        assertEquals(DisplayValue.State.UNKNOWN, empty.perHour().state);
        assertEquals(4, empty.untagged()); assertEquals(1, empty.unreadable());
        assertEquals("The saved fame does not need history", DisplayValue.State.STALE, empty.current().value().state);
        assertEquals(KEY, empty.key());
    }

    @Test public void theCurrentFameIsReplacedAloneAndEqualInputsMakeEqualModels() {
        FameHistory.Series series = series(1, 0, readings(FIRST, false, T0, 100, T0 + 10 * MINUTE, 160));
        FameModel saved = FameModel.build(KEY, series, record(1_234L, T1), null);
        assertEquals(saved, FameModel.build(KEY, series(1, 0, readings(FIRST, false, T0, 100, T0 + 10 * MINUTE, 160)), record(1_234L, T1), null));
        FameModel playing = saved.withCurrent(FameModel.current(KEY, record(1_234L, T1), live(ACCOUNT, 7, 1_600L)));
        assertTrue(playing.current().live());
        assertEquals(saved.perHour(), playing.perHour()); assertEquals(saved.gained(), playing.gained());
        assertSame("Readings are shared, not copied", saved.sessions(), playing.sessions());
        assertEquals(1, playing.untagged());
    }
}
