package tomato.backend.data;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Read-only presence projection of one published asset generation, shared by roster queries. */
public final class RosterDefinitions {
    public static final class Item {
        public final Integer tier, slotType;
        public final Set<String> labels;
        private Item(Integer tier, Integer slotType, Set<String> labels) {
            this.tier = tier; this.slotType = slotType; this.labels = labels == null ? null : Collections.unmodifiableSet(labels);
        }
        public boolean special() { return labels != null && (labels.contains("UT") || labels.contains("ST")); }
        public Boolean parsable() {
            if (labels == null) return null;
            if (labels.contains("CONSUMABLE")) return false;
            if (special()) return true;
            if (tier == null) return null;
            return labels.contains("T" + tier) || labels.contains("ARMOR") && labels.contains("T" + (tier + 1));
        }
    }
    private static final String[] CAP_TAGS = {"MaxHitPoints", "MaxMagicPoints", "Attack", "Defense", "Speed", "Dexterity", "HpRegen", "MpRegen"};
    private final Map<Integer, Integer[]> caps;
    private final Map<Integer, Item> items;
    public final String status;
    private RosterDefinitions(Map<Integer, Integer[]> caps, Map<Integer, Item> items, String status) {
        this.caps = Collections.unmodifiableMap(caps); this.items = Collections.unmodifiableMap(items); this.status = status;
    }
    public Integer cap(int classId, int stat) { Integer[] values = caps.get(classId); return values == null ? null : values[stat]; }
    public Item item(int id) { return items.get(id); }
    public static RosterDefinitions empty() { return new RosterDefinitions(new HashMap<>(), new HashMap<>(), "Definitions unavailable; affected calculations remain unknown"); }

    public static RosterDefinitions parse(Reader players, Reader equipment) throws IOException {
        Map<Integer, Integer[]> caps = new HashMap<>(); Map<Integer, Item> items = new HashMap<>();
        if (players != null) for (Element object : objects(players)) {
            int id = Integer.decode(object.getAttribute("type")); Integer[] values = new Integer[8];
            for (int i = 0; i < 8; i++) {
                Element field = child(object, CAP_TAGS[i]);
                if (field != null && field.hasAttribute("max")) values[i] = nonnegative(field.getAttribute("max"));
            }
            caps.put(id, values);
        }
        if (equipment != null) for (Element object : objects(equipment)) {
            int id = Integer.decode(object.getAttribute("type"));
            Element tier = child(object, "Tier"), slot = child(object, "SlotType"), labels = child(object, "Labels");
            Set<String> tags = null;
            if (labels != null) {
                tags = new HashSet<>();
                for (String tag : labels.getTextContent().split(",")) tags.add(tag.trim().toUpperCase(Locale.ROOT));
            }
            items.put(id, new Item(tier == null ? null : nonnegative(tier.getTextContent()),
                slot == null ? null : nonnegative(slot.getTextContent()), tags));
        }
        return new RosterDefinitions(caps, items, "Local definitions; omitted fields remain unknown");
    }
    private static Integer nonnegative(String text) {
        try {
            String number = text.trim();
            int value = number.startsWith("0x") || number.startsWith("0X") || number.startsWith("#") ? Integer.decode(number) : Integer.parseInt(number);
            return value < 0 ? null : value;
        }
        catch (NumberFormatException invalid) { return null; }
    }
    private static List<Element> objects(Reader reader) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            NodeList nodes = factory.newDocumentBuilder().parse(new InputSource(reader)).getElementsByTagName("Object");
            List<Element> result = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) result.add((Element)nodes.item(i));
            return result;
        } catch (Exception failure) { throw new IOException("Could not read roster definitions", failure); }
    }
    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && name.equals(node.getNodeName())) return (Element)node;
        return null;
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "roster-definitions"); t.setDaemon(true); return t; });
    private static volatile RosterDefinitions current = empty();
    private static Path requested;
    private static boolean running;
    /** Constant-time on the EDT. At most one reader; generation changes supersede its publication. */
    public static RosterDefinitions current() {
        Path root = assets.AssetCache.root();
        synchronized (LOCK) {
            if (!root.equals(requested)) {
                requested = root; current = empty();
                if (!running) { running = true; READER.execute(RosterDefinitions::drain); }
            }
        }
        return current;
    }
    private static void drain() {
        while (true) {
            Path root; synchronized (LOCK) { root = requested; }
            RosterDefinitions classes = read(root.resolve("xml/players.xml"), true);
            RosterDefinitions equipment = read(root.resolve("xml/equip.xml"), false);
            RosterDefinitions loaded = new RosterDefinitions(new HashMap<>(classes.caps), new HashMap<>(equipment.items),
                "Local roster definitions; missing caps/items remain unknown");
            synchronized (LOCK) {
                if (!root.equals(requested)) continue;
                current = loaded; running = false; return;
            }
        }
    }
    private static RosterDefinitions read(Path file, boolean players) {
        try (Reader reader = Files.newBufferedReader(file)) { return parse(players ? reader : null, players ? null : reader); }
        catch (IOException | RuntimeException unavailable) { return empty(); }
    }
}
