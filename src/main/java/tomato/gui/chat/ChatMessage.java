package tomato.gui.chat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import packets.incoming.TextPacket;

/** A snapshot of a received message, independent of the mutable packet. */
final class ChatMessage {
    enum Channel {
        ALL("All"), PM("PM"), PARTY("Party"), GUILD("Guild"), WORLD("World"), SYSTEM("System"), IGNORED("Ignored");
        final String label;
        Channel(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    final LocalDateTime received;
    final Channel channel;
    final String sender, recipient, player, text, direction;
    final boolean ownMessage, gameIgnored;
    private final String searchable, players;

    ChatMessage(LocalDateTime received, Channel channel, String sender, String recipient,
                String player, String text, String direction) {
        this(received, channel, sender, recipient, player, text, direction, "To".equals(direction), false);
    }

    private ChatMessage(LocalDateTime received, Channel channel, String sender, String recipient,
                String player, String text, String direction, boolean ownMessage, boolean gameIgnored) {
        this.received = received;
        this.channel = channel;
        this.sender = safe(sender);
        this.recipient = safe(recipient);
        this.player = safe(player);
        this.text = safe(text);
        this.direction = safe(direction);
        this.ownMessage = ownMessage;
        this.gameIgnored = gameIgnored;
        players = (this.sender + "\n" + this.recipient + "\n" + this.player).toLowerCase(Locale.ROOT);
        searchable = (date() + "\n" + channel.label + "\n" + players + "\n" + this.text).toLowerCase(Locale.ROOT);
    }

    static ChatMessage from(TextPacket packet, String self) {
        String sender = name(packet.name), recipient = name(packet.recipient);
        String rawRecipient = safe(packet.recipient);
        Channel channel = rawRecipient.contains("*Guild*") ? Channel.GUILD
                : rawRecipient.contains("*Party*") ? Channel.PARTY
                : !rawRecipient.trim().isEmpty() ? Channel.PM
                : sender.isEmpty() || sender.startsWith("#") ? Channel.SYSTEM : Channel.WORLD;
        String direction = "", player = sender;
        self = name(self);
        if (channel == Channel.PM && !self.isEmpty()) {
            if (sender.equalsIgnoreCase(self)) { direction = "To"; player = recipient; }
            else if (recipient.equalsIgnoreCase(self)) direction = "From";
        }
        return new ChatMessage(LocalDateTime.now(), channel, sender, recipient, player, packet.text, direction,
                !self.isEmpty() && sender.equalsIgnoreCase(self), false);
    }

    ChatMessage withGameIgnored(boolean ignored) {
        return new ChatMessage(received, channel, sender, recipient, player, text, direction, ownMessage, ignored);
    }

    boolean isFrom(String self) {
        String local = name(self);
        return !local.isEmpty() && sender.equalsIgnoreCase(local);
    }

    /** Only positively identified incoming whispers can trigger a private-message alert. */
    boolean isIncomingWhisper() { return channel == Channel.PM && "From".equals(direction); }

    static ChatMessage notice(String text) {
        return new ChatMessage(LocalDateTime.now(), Channel.SYSTEM, "RealmShark", "", "RealmShark", text, "");
    }

    ChatMessage hint(String text) {
        return new ChatMessage(received, channel, "Umi Response", "", "Umi Response", text, "");
    }

    boolean matches(String query, String playerQuery) {
        return matchesNormalized(query.trim().toLowerCase(Locale.ROOT), playerQuery.trim().toLowerCase(Locale.ROOT));
    }

    boolean matchesNormalized(String query, String playerQuery) {
        return searchable.contains(query) && players.contains(playerQuery);
    }

    String clock() { return CLOCK.format(received); }
    String date() { return DATE.format(received); }
    String playerLabel() { return direction.isEmpty() ? player : direction + ": " + player; }
    String transcript() {
        return date() + " [" + channel.label + "] " + sender
                + (channel == Channel.PM ? " → " + recipient : "") + ": " + text;
    }
    private static String name(String value) { return safe(value).split(",", 2)[0].trim(); }
    private static String safe(String value) { return value == null ? "" : value; }
}
