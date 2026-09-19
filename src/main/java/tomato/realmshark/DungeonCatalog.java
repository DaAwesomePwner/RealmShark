package tomato.realmshark;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import javax.xml.stream.*;
import packets.packetcapture.CaptureDiagnostics;
import packets.incoming.MapInfoPacket;

/** Optional metadata must never prevent game packets or loot entries from being processed. */
final class DungeonCatalog {
    static final String UNRECOGNIZED = "Unrecognized area";
    private final Map<String, Integer> modifiers = new HashMap<>();
    private final Map<String, Area> areas = new HashMap<>();
    private final Set<String> ambiguousDisplays = new HashSet<>();

    private static final class Area {
        final String name;
        final boolean dungeon;
        int portalId;

        Area(String name, boolean dungeon, int portalId) {
            this.name = name;
            this.dungeon = dungeon;
            this.portalId = portalId;
        }
    }

    private static final class Portal {
        String name = "", display = "", objectId = "", objectClass = "", type;
        boolean dungeon, safeZone, locked;
        int id, source;

        int variantRank() {
            // This ranks icons, not area classification. No substring-based dungeon exclusions.
            if (locked) return 1;
            for (String word : objectId.split("[^A-Za-z]+")) {
                if ("Locked".equalsIgnoreCase(word) || "Event".equalsIgnoreCase(word)
                        || "Solo".equalsIgnoreCase(word) || "Temp".equalsIgnoreCase(word)) return 1;
            }
            return 0;
        }
    }

    static DungeonCatalog load(Path directory) {
        DungeonCatalog catalog = new DungeonCatalog();
        BundledAreaCatalog.populate(catalog);
        List<Portal> portals = new ArrayList<>();
        Set<String> templates = new HashSet<>();
        // Streaming XML retains only portal/modifier metadata, not multi-MB object trees.
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.walk(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .sorted().forEach(path -> catalog.read(path, directory, portals, templates));
            } catch (Exception e) {
                CaptureDiagnostics.record("Dungeon metadata scan incomplete; keeping available lookups", e);
            }
        }
        catalog.merge(portals, templates);
        String[] grades = {"S", "A", "B", "C", "D"};
        for (int i = 0; i < grades.length; i++) catalog.modifiers.put("|" + grades[i], -11 - i);
        return catalog;
    }

    void addReviewed(String name, int portalId, boolean dungeon, String... aliases) {
        Area area = new Area(name, dungeon, portalId);
        areas.put(name, area);
        for (String alias : aliases) areas.put(alias, area);
    }

    void addAmbiguousDisplays(String... names) {
        Collections.addAll(ambiguousDisplays, names);
    }

    private void read(Path path, Path directory, List<Portal> portals, Set<String> templates) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader xml = factory.createXMLStreamReader(input);
            boolean modifierFile = path.equals(directory.resolve("mods.xml"))
                    || path.equals(directory.resolve("mods2.xml"));
            int source = path.equals(directory.resolve("portals.xml")) ? 0 : 1;
            Portal portal = null;
            int depth = 0, objectDepth = 0;
            try {
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DOCTYPE is not supported");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        String tag = xml.getLocalName();
                        if ("Object".equals(tag) && portal == null) {
                            portal = new Portal();
                            portal.type = xml.getAttributeValue(null, "type");
                            String objectId = xml.getAttributeValue(null, "id");
                            portal.objectId = objectId == null ? "" : objectId;
                            portal.source = source;
                            objectDepth = depth;
                        } else if (portal != null && depth == objectDepth + 1) {
                            switch (tag) {
                                case "DungeonName": portal.name = xml.getElementText().trim(); depth--; break;
                                case "DisplayId": portal.display = xml.getElementText().trim(); depth--; break;
                                case "Class": portal.objectClass = xml.getElementText().trim(); depth--; break;
                                case "DungeonPortal": portal.dungeon = true; break;
                                case "SafeZonePortal": portal.safeZone = true; break;
                                case "LockedPortal": portal.locked = true; break;
                                default: break;
                            }
                        } else if (modifierFile && "DungeonModifier".equals(tag)) {
                            String name = xml.getAttributeValue(null, "id");
                            Integer id = decodeId(xml.getAttributeValue(null, "type"), path);
                            if (name != null && !name.isEmpty() && id != null) modifiers.put(name, id);
                        } else if ("DungeonModTemplate".equals(tag)) {
                            String name = xml.getAttributeValue(null, "id");
                            if (name != null && !name.isEmpty()) templates.add(name);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (portal != null && depth == objectDepth && "Object".equals(xml.getLocalName())) {
                            // Preserve name/type-only lookups from portals.xml; other object types aren't maps.
                            if (!portal.name.isEmpty() && ("Portal".equals(portal.objectClass)
                                    || "GuildHallPortal".equals(portal.objectClass)
                                    || (source == 0 && portal.objectClass.isEmpty()))) {
                                Integer id = decodeId(portal.type, path);
                                if (id != null && id > 0) {
                                    portal.id = id;
                                    portals.add(portal);
                                }
                            }
                            portal = null;
                        }
                        depth--;
                    }
                }
            } finally {
                xml.close();
            }
        } catch (Exception e) {
            CaptureDiagnostics.record("Dungeon metadata unavailable: " + path.getFileName() + "; keeping available lookups", e);
        }
    }

    private static Integer decodeId(String value, Path path) {
        try {
            return value == null ? null : Integer.decode(value);
        } catch (NumberFormatException e) {
            CaptureDiagnostics.record("Skipping invalid dungeon metadata ID in " + path.getFileName(), e);
            return null;
        }
    }

    private void merge(List<Portal> portals, Set<String> templates) {
        Set<String> content = new HashSet<>(templates);
        Set<String> safeZones = new HashSet<>();
        for (Portal portal : portals) {
            if (portal.dungeon) content.add(portal.name);
            if (portal.safeZone) safeZones.add(portal.name);
        }
        // Existing portals.xml wins; within each source prefer normal portals, then lowest ID.
        portals.sort(Comparator.comparingInt((Portal p) -> p.source)
                .thenComparingInt(Portal::variantRank).thenComparingInt(p -> p.id));
        Set<Area> selected = new HashSet<>();
        for (Portal portal : portals) {
            Area area = areas.get(portal.name);
            if (area == null) {
                boolean dungeon = !safeZones.contains(portal.name) && content.contains(portal.name);
                area = new Area(portal.name, dungeon, portal.id);
                areas.put(portal.name, area);
            }
            if (selected.add(area)) area.portalId = portal.id;
        }
        Map<String, Area> displays = new HashMap<>();
        for (Portal portal : portals) {
            if (portal.display.isEmpty()) continue;
            Area area = areas.get(portal.name);
            Area named = areas.get(portal.display);
            if (named != null && named != area) ambiguousDisplays.add(portal.display);
            Area previous = displays.putIfAbsent(portal.display, area);
            if (previous != null && previous != area) ambiguousDisplays.add(portal.display);
        }
        displays.forEach((name, area) -> {
            if (!ambiguousDisplays.contains(name)) areas.putIfAbsent(name, area);
        });
    }

    int[] getModIds(String value) {
        if (value == null || value.isEmpty()) return new int[0];
        // Unknown names remain in the original MapInfoPacket; never invent a numeric ID.
        return Arrays.stream(value.split(";")).map(modifiers::get).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).toArray();
    }

    int getPortalId(String name) {
        Area area = areas.get(name);
        return area == null ? -1 : area.portalId;
    }

    boolean isDungeon(String name) {
        Area area = areas.get(name);
        return area != null && area.dungeon;
    }

    String canonicalName(String name) {
        Area area = areas.get(name);
        return area == null ? UNRECOGNIZED : area.name;
    }

    String canonicalMapName(MapInfoPacket map) {
        if (map == null) return UNRECOGNIZED;
        Area area = areas.get(map.name);
        if (area != null) return area.name;
        return ambiguousDisplays.contains(map.displayName) ? UNRECOGNIZED : canonicalName(map.displayName);
    }
}
