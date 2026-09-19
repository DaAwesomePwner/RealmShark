package tomato.bridge;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Immutable settings; the property names match the public Tomato bridge. */
public final class BridgeConfig {
    public static final String PREFIX = "realmshark.bridge.";
    public final boolean enabled, debug, send, ut, st, shiny, enchanted, other;
    public final String endpoint, guildId, token, csvPath, reviewLog;

    public BridgeConfig(Properties p) {
        enabled = bool(p, "enabled", false); debug = bool(p, "debug", false);
        send = bool(p, "send", true);
        endpoint = value(p, "endpoint"); guildId = value(p, "guild_id"); token = value(p, "link_token");
        csvPath = value(p, "csv_path"); reviewLog = value(p, "local_review_log");
        ut = bool(p, "filter.ut", true); st = bool(p, "filter.st", true);
        shiny = bool(p, "filter.shiny", true); enchanted = bool(p, "filter.enchanted", true);
        other = bool(p, "filter.other", true);
    }
    private static String value(Properties p, String k) { return p.getProperty(PREFIX + k, "").trim(); }
    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(PREFIX + k);
        return v == null ? d : v.trim().matches("(?i)true|1|yes|on");
    }
    public Properties properties() {
        Properties p = new Properties();
        String[] keys = {"enabled", "debug", "send", "endpoint", "guild_id", "link_token", "csv_path", "local_review_log", "filter.ut", "filter.st", "filter.shiny", "filter.enchanted", "filter.other"};
        Object[] values = {enabled, debug, send, endpoint, guildId, token, csvPath, reviewLog, ut, st, shiny, enchanted, other};
        for (int i=0;i<keys.length;i++) p.setProperty(PREFIX+keys[i], String.valueOf(values[i]));
        return p;
    }
    public void validate() {
        if (!enabled) return;
        if (csvPath.isEmpty()) throw new IllegalArgumentException("Choose a loot CSV containing an Item Name column.");
        Paths.get(csvPath);
        if (!reviewLog.isEmpty()) {
            Path log = Paths.get(reviewLog).toAbsolutePath().normalize();
            if (log.equals(Paths.get(csvPath).toAbsolutePath().normalize()) || log.equals(Paths.get("bridge.properties").toAbsolutePath().normalize()) || log.equals(Paths.get("realmShark.properties").toAbsolutePath().normalize()))
                throw new IllegalArgumentException("The review log must be a separate file from settings and the loot CSV.");
        }
        if (!send) return;
        try {
            URI uri = URI.create(endpoint);
            boolean local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null || !("https".equalsIgnoreCase(uri.getScheme()) || local && "http".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IllegalArgumentException("Endpoint must be an HTTPS URL without embedded credentials, query or fragment (HTTP is allowed for localhost)."); }
        try { if (!guildId.matches("[0-9]+") || Long.parseLong(guildId)<=0) throw new NumberFormatException(); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Guild ID must be a positive Discord server ID."); }
        if (token.isEmpty()) throw new IllegalArgumentException("Enter the Link Token from your guild bot.");
    }
    public boolean includes(BridgePayload.Item item) {
        return ut && item.ut || st && item.st || shiny && item.shiny || enchanted && item.enchantCount>0
            || other && !item.ut && !item.st && !item.shiny && item.enchantCount<=0;
    }
    public static BridgeConfig load(Path file) throws IOException {
        Properties p = new Properties();
        Path source = Files.exists(file) ? file : file.resolveSibling("realmShark.properties");
        if (Files.exists(source)) try (Reader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) { p.load(r); }
        return new BridgeConfig(p);
    }
    public void save(Path file) throws IOException {
        Path absolute = file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "bridge-", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { properties().store(w, "RealmShark Bridge - contains your private link token; do not share"); }
            try { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
