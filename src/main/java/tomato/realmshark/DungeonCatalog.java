package tomato.realmshark;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import packets.packetcapture.CaptureDiagnostics;

/** Optional metadata must never prevent game packets or loot entries from being processed. */
final class DungeonCatalog {
    private final Map<String, Integer> modifiers = new HashMap<>();
    private final Map<String, Integer> portals = new HashMap<>();

    static DungeonCatalog load(Path directory) {
        DungeonCatalog catalog = new DungeonCatalog();
        catalog.read(directory.resolve("mods.xml"), true);
        // Some game builds include all modifiers in mods.xml and have no mods2.xml.
        if (Files.exists(directory.resolve("mods2.xml"))) catalog.read(directory.resolve("mods2.xml"), true);
        catalog.read(directory.resolve("portals.xml"), false);
        String[] grades = {"S", "A", "B", "C", "D"};
        for (int i = 0; i < grades.length; i++) catalog.modifiers.put("|" + grades[i], -11 - i);
        catalog.portals.put("Realm of the Mad God", 1796);
        return catalog;
    }

    private void read(Path path, boolean modifierFile) {
        try (InputStream input = Files.newInputStream(path)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            NodeList entries = factory.newDocumentBuilder().parse(input).getElementsByTagName(
                    modifierFile ? "DungeonModifier" : "Object");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element)entries.item(i);
                try {
                    int id = Integer.decode(entry.getAttribute("type"));
                    if (modifierFile) {
                        String name = entry.getAttribute("id");
                        if (!name.isEmpty()) modifiers.put(name, id);
                    } else {
                        NodeList names = entry.getElementsByTagName("DungeonName");
                        if (names.getLength() > 0 && id != 0) portals.put(names.item(0).getTextContent().trim(), id);
                    }
                } catch (NumberFormatException e) {
                    CaptureDiagnostics.record("Skipping invalid dungeon metadata ID in " + path.getFileName(), e);
                }
            }
        } catch (Exception e) {
            CaptureDiagnostics.record("Dungeon metadata unavailable: " + path.getFileName() + "; keeping available lookups", e);
        }
    }

    int[] getModIds(String value) {
        if (value == null || value.isEmpty()) return new int[0];
        // Unknown names remain in the original MapInfoPacket; never invent a numeric ID.
        return Arrays.stream(value.split(";")).map(modifiers::get).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).toArray();
    }

    int getPortalId(String name) { return portals.getOrDefault(name, -1); }
}
