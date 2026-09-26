package tomato.planning;

import assets.AssetCache;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import tomato.backend.data.RosterDefinitions;

/** Optional selected-generation metadata. No network, guessed aliases or unrelated fallback files. */
public final class PlanningMetadata {
    public static final String THRESHOLD_VERSION = "local-thresholds-v1:5,15,30,50,75";
    private static final int[] THRESHOLDS = {5, 15, 30, 50, 75};
    public final String version, status;
    public final boolean available;
    private final Map<Integer, List<String>> dungeons;
    private PlanningMetadata(String version, String status, boolean available, Map<Integer, List<String>> dungeons) {
        this.version = version; this.status = status; this.available = available;
        Map<Integer, List<String>> copy = new LinkedHashMap<>();
        dungeons.forEach((key, value) -> copy.put(key, Collections.unmodifiableList(new ArrayList<>(value))));
        this.dungeons = Collections.unmodifiableMap(copy);
    }
    public List<String> dungeons(int stat) { return dungeons.getOrDefault(stat, Collections.emptyList()); }
    public static int threshold(int tier) {
        if (tier < 1 || tier > 5) throw new IllegalArgumentException("Tier must be 1–5");
        return THRESHOLDS[tier - 1];
    }
    public static String capVersion(RosterDefinitions definitions, int classId) {
        StringBuilder value = new StringBuilder("local-caps-v1:").append(classId);
        for (int i = 0; i < 8; i++) value.append(':').append(Objects.toString(definitions.cap(classId, i), "unknown"));
        return value.toString();
    }
    public static PlanningMetadata unavailable() {
        return new PlanningMetadata(THRESHOLD_VERSION + ":mapping-unavailable", "Dungeon mappings unavailable in selected assets; local threshold schedule remains 5 / 15 / 30 / 50 / 75", false, Collections.emptyMap());
    }
    public static PlanningMetadata read(Path root) {
        Path file = root.resolve("xml/exaltationConfig.xml");
        try {
            if (Files.size(file) > 1024 * 1024) return unavailable();
            byte[] bytes = Files.readAllBytes(file);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            Map<Integer, List<String>> values = new LinkedHashMap<>();
            List<String> codes = Arrays.asList("LIFE", "MANA", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS");
            NodeList nodes = doc.getElementsByTagName("Dungeon"); int omitted = 0;
            for (int i = 0; i < nodes.getLength(); i++) {
                Element dungeon = (Element)nodes.item(i);
                if (!(dungeon.getParentNode() instanceof Element) || !"Dungeons".equals(dungeon.getParentNode().getNodeName())) continue;
                String name = child(dungeon, "Name"), power = child(dungeon, "PowerUp"); int stat = codes.indexOf(power);
                if (name.isEmpty() || name.length() > 256 || stat < 0) { omitted++; continue; }
                List<String> list = values.computeIfAbsent(stat, key -> new ArrayList<>()); if (!list.contains(name)) list.add(name);
            }
            StringBuilder hash = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            return new PlanningMetadata(THRESHOLD_VERSION + ":mapping-sha256:" + hash, "Selected local exaltationConfig.xml · " + values.size() + "/8 stats mapped · " + omitted + " unsupported entries · alias/current-game coverage unverified", !values.isEmpty(), values);
        } catch (Exception unavailable) { return unavailable(); }
    }
    private static String child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element && name.equals(n.getNodeName())) return n.getTextContent().trim();
        return "";
    }
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "planning-metadata"); t.setDaemon(true); return t; });
    private static PlanningMetadata current = unavailable();
    private static Path requested;
    private static boolean running;
    /** Non-blocking on EDT; publication belongs to the generation that requested it. */
    public static synchronized PlanningMetadata current() {
        Path root = AssetCache.root();
        if (!root.equals(requested)) {
            requested = root; current = unavailable();
            if (!running) { running = true; READER.execute(PlanningMetadata::drain); }
        }
        return current;
    }
    private static void drain() {
        while (true) {
            Path root; synchronized (PlanningMetadata.class) { root = requested; }
            PlanningMetadata loaded = read(root);
            synchronized (PlanningMetadata.class) {
                if (!root.equals(requested)) continue;
                current = loaded; running = false; return;
            }
        }
    }
}
