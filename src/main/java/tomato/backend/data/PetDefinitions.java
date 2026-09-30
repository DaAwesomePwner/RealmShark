package tomato.backend.data;

import assets.AssetCache;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Pet names from the selected game assets. Family names come only from {@code <assetRoot>/xml/pets.xml}: the {@code <Family>} text
 * of the object whose {@code type} is the pet's type ({@code PetRecord.type}, PET_TYPE_STAT 83). The file's structure cannot be
 * verified in the repo, so every tag is optional and anything unreadable stays unknown. The numeric PET_FAMILY_STAT (86) is never
 * decoded to a name. Rarity (0-based, in the order EnchantInfo.Rarity lists item rarity) and ability names are fixed tables. Loads like
 * PlanningMetadata: lazily on one background reader, keyed on AssetCache.root(), re-read when the root changes.
 */
public final class PetDefinitions {
    /** Larger than any plausible pets.xml; the whole file is read into memory and parsed as a DOM. */
    static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final String[] RARITY = {"Common", "Uncommon", "Rare", "Legendary", "Divine"};
    public final boolean available;
    public final String status;
    private final Map<Integer, String> families;
    private PetDefinitions(boolean available, String status, Map<Integer, String> families) {
        this.available = available; this.status = status; this.families = Collections.unmodifiableMap(new HashMap<>(families));
    }

    public static PetDefinitions loading() { return new PetDefinitions(false, "Pet names load with the selected game assets", Collections.emptyMap()); }
    public static PetDefinitions unavailable() { return new PetDefinitions(false, "Pet names are unavailable in the selected game assets", Collections.emptyMap()); }

    /** The {@code <Family>} text of the pet type object with this id; null when unknown or not loaded. */
    public String family(Integer petType) { return petType == null ? null : families.get(petType); }

    /** 0 Common, 1 Uncommon, 2 Rare, 3 Legendary, 4 Divine; null for null or any other value (never guessed). */
    public static String rarity(Integer rarity) { return rarity == null || rarity < 0 || rarity >= RARITY.length ? null : RARITY[rarity]; }

    /** Ability names for ids 402–411 exactly as CharacterPetsGUI names them; null for 403, any other id or a negative id. */
    public static String ability(int abilityType) {
        switch (abilityType) {
            case 402: return "Attack close"; case 404: return "Attack mid"; case 405: return "Attack far"; case 406: return "Electric";
            case 407: return "Heal"; case 408: return "Magic heal"; case 409: return "Savage"; case 410: return "Decoy";
            case 411: return "Rising fury"; default: return null;
        }
    }

    /** {@code <root>/xml/pets.xml}; unavailable() when the file is missing, too large or malformed. */
    public static PetDefinitions read(Path root) {
        Path file = root.resolve("xml/pets.xml");
        try {
            if (Files.size(file) > MAX_BYTES) return unavailable();
            return parse(Files.readAllBytes(file));
        } catch (Exception unavailable) { return unavailable(); }
    }

    /** Test seam. Every {@code <Object>} with a parseable type and a non-blank direct {@code <Family>} child names that type; duplicates keep the first. */
    static PetDefinitions parse(byte[] xml) {
        try {
            if (xml.length > MAX_BYTES) return unavailable();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            Map<Integer, String> families = new HashMap<>();
            NodeList nodes = doc.getElementsByTagName("Object");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element object = (Element)nodes.item(i);
                Integer type = type(object.getAttribute("type"));
                String family = family(object);
                if (type != null && !family.isEmpty() && family.length() <= 256) families.putIfAbsent(type, family);
            }
            String status = families.isEmpty() ? "The selected game assets name no pet families; families stay unknown"
                : "Pet families from the selected game assets (" + families.size() + (families.size() == 1 ? " pet type" : " pet types")
                    + "); a type they do not name stays unknown";
            return new PetDefinitions(true, status, families);
        } catch (Exception unavailable) { return unavailable(); }
    }
    /** Hex ({@code 0x…}) or decimal; null when absent, malformed or negative. */
    private static Integer type(String text) {
        try {
            String number = text.trim();
            if (number.isEmpty()) return null;
            int value = number.startsWith("0x") || number.startsWith("0X") ? Integer.decode(number) : Integer.parseInt(number);
            return value < 0 ? null : value;
        } catch (NumberFormatException invalid) { return null; }
    }
    /** The first direct {@code <Family>} child's text, trimmed; "" when none or when it holds markup (not a plain name, so unknown). */
    private static String family(Element object) {
        for (Node n = object.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element) || !"Family".equals(n.getNodeName())) continue;
            StringBuilder text = new StringBuilder();
            for (Node t = n.getFirstChild(); t != null; t = t.getNextSibling()) {
                if (t instanceof Element) return "";
                if (t instanceof Text) text.append(t.getNodeValue()); // CDATA included; comments are not text
            }
            return text.toString().trim();
        }
        return "";
    }

    private static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "pet-definitions"); t.setDaemon(true); return t; });
    private static PetDefinitions current = loading();
    private static Path requested;
    private static boolean running;
    /** Test pins, newest last; guarded by the class lock. */
    private static final List<Pin> PINS = new ArrayList<>();
    private static final class Pin { final PetDefinitions value; Pin(PetDefinitions value) { this.value = value; } }

    /**
     * The selected assets' pet names: loading() until the background read for the current root finishes, unavailable() without the
     * file. Never blocks (the EDT may call it); the value stays the same object until the root changes or the read publishes.
     */
    public static synchronized PetDefinitions current() {
        if (!PINS.isEmpty()) return PINS.get(PINS.size() - 1).value;
        Path root = AssetCache.root();
        if (!root.equals(requested)) {
            requested = root; current = loading();
            if (!running) { running = true; READER.execute(PetDefinitions::drain); }
        }
        return current;
    }
    /** Publication belongs to the root that requested it; a root change during a read reads again. */
    private static void drain() {
        while (true) {
            Path root; synchronized (PetDefinitions.class) { root = requested; }
            PetDefinitions loaded = read(root);
            synchronized (PetDefinitions.class) {
                if (!root.equals(requested)) continue;
                current = loaded; running = false; return;
            }
        }
    }

    /**
     * Tests only: pins current() to {@code value} until the returned handle is closed. Pins nest (the newest wins) and may be
     * closed in any order; closing twice is harmless. No asset read starts while pinned.
     */
    public static AutoCloseable install(PetDefinitions value) {
        Pin pin = new Pin(Objects.requireNonNull(value, "value"));
        synchronized (PetDefinitions.class) { PINS.add(pin); }
        return () -> { synchronized (PetDefinitions.class) { PINS.removeIf(p -> p == pin); } };
    }
}
