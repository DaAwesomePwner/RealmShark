package tomato.gui.stats;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import tomato.gui.modern.DisplayFormat;

/**
 * Shared formatting helpers for stats views.
 *
 * Centralizes common number and time formatting so the UI stays consistent
 * and logic isn't duplicated across panels.
 */
public final class Formatters {

    // Common date/time patterns used across the UI
    public static final DateTimeFormatter DATE_TIME = DisplayFormat.DATE_TIME;
    public static final DateTimeFormatter TIME_SHORT = DisplayFormat.TIME;
    public static final DateTimeFormatter DATE_TIME_COMPACT = DateTimeFormatter.ofPattern("yyyy/MM/dd-HH:mm:ss", Locale.ROOT);

    private Formatters() {
        // no instances
    }

    // -----------------------
    // Number formatting
    // -----------------------

    /**
     * If the value is an integer, render without decimals.
     * Otherwise, preserve its decimal representation (no rounding), with locale punctuation.
     */
    public static String formatNumberExact(double number) {
        return DisplayFormat.formatExact(number);
    }

    /**
     * Render a double with a fixed number of decimal places.
     */
    public static String formatNumber(double number, int decimals) {
        return DisplayFormat.formatNumber(number, decimals);
    }

    /**
     * Render Fame per hour with 2 decimals by default.
     */
    public static String formatFamePerHour(double famePerHour) {
        return DisplayFormat.formatRate(famePerHour, 2);
    }

    /**
     * Render Fame per minute with 2 decimals by default.
     */
    public static String formatFamePerMinute(double famePerMinute) {
        return DisplayFormat.formatRate(famePerMinute, 2);
    }

    /**
     * Render a fame delta with the given precision (useful for "Session Gain" or map fame tables).
     */
    public static String formatFame(double fame, int decimals) {
        return formatNumber(fame, decimals);
    }

    // -----------------------
    // Time + duration formatting
    // -----------------------

    /**
     * Format an epoch millis timestamp using the local system zone and "yyyy-MM-dd HH:mm:ss".
     */
    public static String formatTimestamp(long epochMillis) {
        return DisplayFormat.formatTimestamp(epochMillis);
    }

    /**
     * Format current local time as "HH:mm:ss".
     */
    public static String formatNowShort() {
        return DisplayFormat.formatTimestamp(Instant.now(), DisplayFormat.TimestampMode.TIME);
    }

    /**
     * Format current local date-time as "yyyy/MM/dd-HH:mm:ss".
     */
    public static String formatNowCompact() {
        return DATE_TIME_COMPACT.format(Instant.now().atZone(ZoneId.systemDefault()));
    }

    /**
     * Format a duration (milliseconds) as HH:mm:ss.
     * Negative values are clamped to 0 for display.
     */
    public static String formatDurationHMS(long durationMillis) {
        return DisplayFormat.formatDurationHMS(durationMillis);
    }

    /**
     * Convenience for converting fame per hour to per minute.
     */
    public static double toPerMinute(double perHour) {
        return perHour / 60.0;
    }

    /**
     * Convenience for converting fame per minute to per hour.
     */
    public static double toPerHour(double perMinute) {
        return perMinute * 60.0;
    }
}
