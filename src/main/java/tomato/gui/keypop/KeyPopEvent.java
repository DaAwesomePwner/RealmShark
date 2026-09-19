package tomato.gui.keypop;

import assets.IdToAsset;
import com.google.gson.Gson;
import packets.data.enums.NotificationEffectType;
import packets.incoming.NotificationPacket;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An observed pop notification; portal callouts are deliberately not pop events. */
final class KeyPopEvent {
    enum Kind {
        KEY("Key"), RUNE("Rune"), VIAL("Vial"), INC("Inc"), OTHER("Other");
        final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final Pattern FIELD = Pattern.compile("\"(player|name)\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")");
    private static final Pattern LEGACY = Pattern.compile("^(\\d{2}:\\d{2}(?::\\d{2})?) \\[([^\\]]+)\\]: (.+)$");
    private static final Gson JSON = new Gson();
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    final Instant time;
    final String player;
    final String item;
    final Kind kind;

    KeyPopEvent(Instant time, String player, String item, Kind kind) {
        this.time = time;
        this.player = player;
        this.item = item;
        this.kind = kind;
    }

    static String field(String message, String name) {
        if (message == null) return "";
        Matcher matcher = FIELD.matcher(message);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                try { return JSON.fromJson(matcher.group(2), String.class).trim(); }
                catch (RuntimeException invalid) { return ""; }
            }
        }
        return "";
    }

    static KeyPopEvent fromPacket(NotificationPacket packet, Instant now) {
        if (packet == null || packet.message == null) return null;
        String player = field(packet.message, "player").split(",", 2)[0].trim();
        if (player.isEmpty()) return null;
        if (packet.effect == NotificationEffectType.PortalOpened) {
            String dungeon = IdToAsset.objectName(packet.pictureType);
            if (dungeon == null || dungeon.trim().isEmpty()) {
                dungeon = "Unknown portal (0x" + Integer.toHexString(packet.pictureType).toUpperCase(Locale.ROOT) + ")";
            }
            return new KeyPopEvent(now, player, dungeon, Kind.KEY);
        }
        if (packet.effect != NotificationEffectType.ServerMessage) return null;
        String name = field(packet.message, "name");
        if (name.contains("Monument has been activated")) {
            Matcher rune = Pattern.compile("^(?:The )?(.+?) Monument has been activated[.!]?$", Pattern.CASE_INSENSITIVE).matcher(name);
            if (rune.matches()) return new KeyPopEvent(now, player, rune.group(1) + " Rune", Kind.RUNE);
        }
        if (name.equals("The Void")) return new KeyPopEvent(now, player, "Vial", Kind.VIAL);
        if (name.equals("Wine Cellar")) return new KeyPopEvent(now, player, "Inc", Kind.INC);
        return null;
    }

    static KeyPopEvent fromLegacy(String line, Instant now) {
        Matcher matcher = LEGACY.matcher(line.trim());
        if (!matcher.matches()) return null;
        String item = matcher.group(3);
        Kind kind = item.endsWith(" Rune") ? Kind.RUNE : item.equals("Vial") ? Kind.VIAL : item.equals("Inc") ? Kind.INC : Kind.OTHER;
        // Legacy text lacks a date and proof of event type. Keep its receipt time and avoid inventing a key count.
        return new KeyPopEvent(now, matcher.group(2), item, kind);
    }

    boolean matches(String query, String type, String itemFilter, Instant since) {
        String searchable = (player + " " + item + " " + kind.label).toLowerCase(Locale.ROOT);
        for (String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!searchable.contains(word)) return false;
        }
        return (type.equals("All types") || kind.label.equals(type))
            && (itemFilter.equals("All dungeons / items") || item.equals(itemFilter))
            && (since == null || !time.isBefore(since));
    }

    String logLine() { return TIME.format(time) + " [" + player + "]: " + item; }

    String csvLine() { return csv(time.toString()) + "," + csv(player) + "," + csv(kind.label) + "," + csv(item) + "\r\n"; }

    static String csv(String value) {
        // Quoting alone does not stop spreadsheets from evaluating a captured name as a formula.
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
