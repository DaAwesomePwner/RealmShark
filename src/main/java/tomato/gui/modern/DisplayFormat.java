package tomato.gui.modern;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human-readable values only; never use for persistence, exports, identifiers or filenames. */
public final class DisplayFormat {
    public static final String UNAVAILABLE = "—";
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    public enum TimestampMode { FULL, TIME, DATE }

    private DisplayFormat() {}

    // Per-call instances pick up FORMAT-locale changes and never share mutable NumberFormat state.
    private static NumberFormat numberFormat(int minimumDecimals, int maximumDecimals) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault(Locale.Category.FORMAT));
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(Math.max(0, minimumDecimals));
        format.setMaximumFractionDigits(Math.max(Math.max(0, minimumDecimals), maximumDecimals));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    /** Integral counts never pass through double (including values beyond 2^53). */
    public static String formatInteger(long value) {
        return numberFormat(0, 0).format(value);
    }

    public static String formatInteger(Long value) {
        return value == null ? UNAVAILABLE : formatInteger(value.longValue());
    }

    public static String formatNumber(double value, int decimals) {
        return formatNumber(value, decimals, decimals);
    }

    /** Explicit domain precision, rounded HALF_UP; a range permits optional trailing decimal places. */
    public static String formatNumber(double value, int minimumDecimals, int maximumDecimals) {
        return Double.isFinite(value)
            ? numberFormat(minimumDecimals, maximumDecimals).format(BigDecimal.valueOf(value)) : UNAVAILABLE;
    }

    /** Preserve the decimal representation of the source number without rounding or adding decimals. */
    public static String formatExact(Number value) {
        if (value == null || (value instanceof Double && !Double.isFinite(value.doubleValue()))
            || (value instanceof Float && !Float.isFinite(value.floatValue()))) return UNAVAILABLE;
        BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
        return numberFormat(0, Math.max(0, decimal.scale())).format(decimal);
    }

    public static String formatRate(double value, int decimals) {
        return formatNumber(value, decimals);
    }

    /** Input is percentage points, not a fraction: 10.5 -> 10,5% in Germany (no added space). */
    public static String formatPercentage(double percentagePoints, int decimals) {
        return Double.isFinite(percentagePoints) ? formatNumber(percentagePoints, decimals) + "%" : UNAVAILABLE;
    }

    /** HH:mm:ss, with unbounded hours. Negative elapsed durations retain the legacy zero clamp. */
    public static String formatDurationHMS(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** Precise combat duration, retaining milliseconds rather than rounding to whole seconds. */
    public static String formatDurationMillis(long millis) {
        char decimal = DecimalFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT)).getDecimalSeparator();
        return formatDurationHMS(millis) + decimal + String.format(Locale.ROOT, "%03d", Math.max(0, millis) % 1000);
    }

    /** Signed seconds, for relative hit times and diagnostic intervals; caller supplies the unit. */
    public static String formatDurationSeconds(long millis, int decimals) {
        return numberFormat(decimals, decimals).format(BigDecimal.valueOf(millis, 3));
    }

    public static String formatTimestamp(long epochMillis) {
        return formatTimestamp(Instant.ofEpochMilli(epochMillis));
    }

    public static String formatTimestamp(Instant instant) {
        return formatTimestamp(instant, TimestampMode.FULL);
    }

    /** Calendar/time punctuation stays unambiguous; the system zone is resolved on every call. */
    public static String formatTimestamp(Instant instant, TimestampMode mode) {
        if (instant == null) return UNAVAILABLE;
        DateTimeFormatter format = mode == TimestampMode.TIME ? TIME : mode == TimestampMode.DATE ? DATE : DATE_TIME;
        return format.format(instant.atZone(ZoneId.systemDefault()));
    }

    public static String timestampZoneLabel() {
        return ZoneId.systemDefault().getId();
    }
}
