package tomato.gui.kit;

import java.util.Locale;
import java.util.function.LongSupplier;
import org.junit.*;
import static org.junit.Assert.*;

public class KitFormatTest {
    private static final long NOW = 1_800_000_000_000L;
    private Locale previous;
    private LongSupplier previousClock;

    @Before public void fix() {
        previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        previousClock = KitFormat.clock;
        KitFormat.clock = () -> NOW;
    }
    @After public void restore() {
        Locale.setDefault(Locale.Category.FORMAT, previous);
        KitFormat.clock = previousClock;
    }

    @Test public void relativeTimeUsesShortPlayerFacingSteps() {
        assertEquals("just now", KitFormat.relative(NOW - 30_000));
        assertEquals("just now", KitFormat.relative(NOW + 60_000));
        assertEquals("12 min ago", KitFormat.relative(NOW - 12 * 60_000));
        assertEquals("3 h ago", KitFormat.relative(NOW - 3 * 3_600_000));
        assertEquals("yesterday", KitFormat.relative(NOW - 30 * 3_600_000L));
        assertEquals("4 days ago", KitFormat.relative(NOW - 4 * 86_400_000L));
        assertTrue(KitFormat.relative(NOW - 30 * 86_400_000L).matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test public void durationsAreCompact() {
        assertEquals("42s", KitFormat.duration(42_900));
        assertEquals("9m 51s", KitFormat.duration(591_000));
        assertEquals("1h 12m", KitFormat.duration(4_320_000));
        assertEquals("—", KitFormat.duration(-1));
    }

    @Test public void compactNumbersKeepOneDecimalAtMost() {
        assertEquals("25", KitFormat.compact(25));
        assertEquals("25.5", KitFormat.compact(25.5));
        assertEquals("999.9", KitFormat.compact(999.94));
        assertEquals("1.5k", KitFormat.compact(1500));
        assertEquals("41.2k", KitFormat.compact(41_234));
        assertEquals("2.3M", KitFormat.compact(2_300_000));
        assertEquals("1M", KitFormat.compact(999_960));
        assertEquals("-1.5k", KitFormat.compact(-1500));
        assertEquals("—", KitFormat.compact(Double.NaN));
    }
}
