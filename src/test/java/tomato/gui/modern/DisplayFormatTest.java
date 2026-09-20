package tomato.gui.modern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.Test;
import tomato.gui.stats.Formatters;
import static org.junit.Assert.*;

public class DisplayFormatTest {
    @Test public void formatCategoryLocaleIsReadPerCallAndLongsAndExactDecimalsKeepTheirPrecision() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertEquals("9,007,199,254,740,993", DisplayFormat.formatInteger(9007199254740993L));
            assertEquals("-9,223,372,036,854,775,808", DisplayFormat.formatInteger(Long.MIN_VALUE));
            assertEquals("10.5%", DisplayFormat.formatPercentage(10.5, 1));
            assertEquals("-1,234.50", DisplayFormat.formatRate(-1234.5, 2));
            assertEquals("1.01", DisplayFormat.formatNumber(1.005, 2));
            assertEquals("-1.01", DisplayFormat.formatNumber(-1.005, 2));
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("9.007.199.254.740.993", DisplayFormat.formatInteger(9007199254740993L));
            assertEquals("9.223.372.036.854.775.807", DisplayFormat.formatExact(Long.MAX_VALUE));
            assertEquals("5,94", DisplayFormat.formatExact(5.94f));
            assertEquals("0,000000000000000000001", DisplayFormat.formatExact(new BigDecimal("1e-21")));
            assertEquals("10,5%", DisplayFormat.formatPercentage(10.5, 1));
            assertEquals("-1.234,50", Formatters.formatFamePerHour(-1234.5));
            assertEquals("1.234,5", DisplayFormat.formatNumber(1234.5, 0, 2));
            assertEquals("1.234", DisplayFormat.formatNumber(1234, 0, 2));
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void exactFormattingPreservesBigDecimalDigitsAndTheActualDoubleRepresentation() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        double binary = 1234.1234567890123;
        BigDecimal decimal = new BigDecimal("1234.1234567890123");
        BigDecimal precise = new BigDecimal("9007199254740993.12345678901234567890123456789");
        try {
            // On JDK 17 this binary64 literal's round-trip decimal ends in 4, before formatting.
            assertEquals("1234.1234567890124", Double.toString(binary));
            assertEquals("1234.1234567890123", decimal.toPlainString());
            assertTrue(decimal.compareTo(BigDecimal.valueOf(binary)) != 0);

            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertEquals("1,234.1234567890124", DisplayFormat.formatExact(binary));
            assertEquals("1,234.1234567890124", Formatters.formatNumberExact(binary));
            assertEquals("1,234.1234567890123", DisplayFormat.formatExact(decimal));
            assertEquals("9,007,199,254,740,993.12345678901234567890123456789", DisplayFormat.formatExact(precise));

            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("-1.234,1234567890124", DisplayFormat.formatExact(-binary));
            assertEquals("-1.234,1234567890124", Formatters.formatNumberExact(-binary));
            assertEquals("-1.234,1234567890123", DisplayFormat.formatExact(decimal.negate()));
            assertEquals("-9.007.199.254.740.993,12345678901234567890123456789", DisplayFormat.formatExact(precise.negate()));
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void unavailableNeverLooksLikeObservedZero() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertEquals("—", DisplayFormat.formatInteger((Long)null));
            assertEquals("—", DisplayFormat.formatExact(null));
            assertEquals("—", DisplayFormat.formatTimestamp((Instant)null));
            for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertEquals("—", Formatters.formatNumberExact(value));
                assertEquals("—", Formatters.formatNumber(value, 1));
                assertEquals("—", DisplayFormat.formatRate(value, 2));
                assertEquals("—", DisplayFormat.formatPercentage(value, 1));
            }
            assertEquals("0", DisplayFormat.formatInteger(0));
            assertEquals("0", DisplayFormat.formatExact(-0.0));
            assertEquals("0.0", DisplayFormat.formatRate(0, 1));
            assertEquals("0.0%", DisplayFormat.formatPercentage(0, 1));
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); }
    }

    @Test public void timestampsFollowZoneChangesAndDurationsRetainTheirExplicitPrecision() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            long time = Instant.parse("2026-01-02T03:04:05Z").toEpochMilli();
            assertEquals("2026-01-02 03:04:05", Formatters.formatTimestamp(time));
            assertEquals("1970-01-01 00:00:00", Formatters.formatTimestamp(0));
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals("2026-01-01 19:04:05", Formatters.formatTimestamp(time));
            assertEquals("America/Los_Angeles", DisplayFormat.timestampZoneLabel());
            assertEquals("19:04:05", DisplayFormat.formatTimestamp(Instant.ofEpochMilli(time), DisplayFormat.TimestampMode.TIME));
            assertEquals("2026-01-01", DisplayFormat.formatTimestamp(Instant.ofEpochMilli(time), DisplayFormat.TimestampMode.DATE));
            assertEquals("100:02:03", Formatters.formatDurationHMS(360123456));
            assertEquals("100:02:03,456", DisplayFormat.formatDurationMillis(360123456));
            assertEquals("00:00:00", Formatters.formatDurationHMS(-1));
            assertEquals("-1,25", DisplayFormat.formatDurationSeconds(-1250, 2));
            assertEquals("9.223.372.036.854.775,807", DisplayFormat.formatDurationSeconds(Long.MAX_VALUE, 3));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
            TimeZone.setDefault(zone);
        }
    }
}
