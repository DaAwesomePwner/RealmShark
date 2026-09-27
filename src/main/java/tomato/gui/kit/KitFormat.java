package tomato.gui.kit;

import java.time.Instant;
import java.time.ZoneId;
import java.util.function.LongSupplier;
import tomato.gui.modern.DisplayFormat;

/** Short human-readable text for glance surfaces. Never use for persistence or exports. */
public final class KitFormat {
    private static final String[] SUFFIXES = {"", "k", "M", "B"};
    /** Replaced by tests only. */
    static LongSupplier clock = System::currentTimeMillis;

    private KitFormat() {}

    /** "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", then the date. */
    public static String relative(long epochMillis) {
        long minutes = Math.max(0, clock.getAsLong() - epochMillis) / 60_000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        long days = hours / 24;
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        return DisplayFormat.DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** "42s", "9m 51s", "1h 12m". Negative durations are unknown. */
    public static String duration(long millis) {
        if (millis < 0) return DisplayFormat.UNAVAILABLE;
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + seconds % 60 + "s";
        return minutes / 60 + "h " + minutes % 60 + "m";
    }

    /** 25, 25.5, 41.2k, 2.3M: one decimal at most, promoted before it would round up to 1,000. */
    public static String compact(double value) {
        if (!Double.isFinite(value)) return DisplayFormat.UNAVAILABLE;
        int index = 0;
        double scaled = value;
        while (index < SUFFIXES.length - 1 && Math.abs(scaled) >= 999.95) { scaled /= 1000; index++; }
        return DisplayFormat.formatNumber(scaled, 0, 1) + SUFFIXES[index];
    }
}
