package tomato.realmshark;

import org.xml.sax.SAXException;
import util.StringXML;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;

public class ParseEquipment {
    private static final String XML_PATH = "assets/xml/equip.xml";
    private static volatile HashMap<Integer, Equipment> EQUIPMENT = new HashMap<>();

    /*
      Load Enchant XML data to get names from file.
     */
    static {
        reload(java.nio.file.Paths.get(XML_PATH));
    }

    public static boolean reload(java.nio.file.Path path) {
        HashMap<Integer, Equipment> next = new HashMap<>();
        try (FileInputStream file = new FileInputStream(path.toFile())) {
            String result = new BufferedReader(new InputStreamReader(file)).lines().collect(Collectors.joining("\n"));
            StringXML base = StringXML.getParsedXML(result);
            for (StringXML xml : base) {
                if (Objects.equals(xml.name, "Object")) {
                    ParseEquipment.Equipment equipment = new ParseEquipment.Equipment();

                    for (StringXML info : xml) {
                        if (Objects.equals(info.name, "id")) {
                            equipment.name = info.value;
                        }
                        if (Objects.equals(info.name, "type")) {
                            equipment.id = Integer.decode(info.value);
                        }
                        if (Objects.equals(info.name, "SlotType")) {
                            equipment.slotType = Integer.decode(info.children.get(0).value);
                        }
                        if (Objects.equals(info.name, "Tier")) {
                            equipment.tier = Integer.decode(info.children.get(0).value);
                        }
                        if (Objects.equals(info.name, "Description")) {
                            equipment.description = info.children.get(0).value;
                        }
                        if (Objects.equals(info.name, "feedPower")) {
                            equipment.feedpower = Integer.decode(info.children.get(0).value);
                        }
                        if (Objects.equals(info.name, "Labels")) {
                            equipment.labels = info.children.get(0).value;
                        }
                        if (Objects.equals(info.name, "DisplayId")) {
                            equipment.displayId = info.children.get(0).value;
                        }
                    }
                    next.put(equipment.id, equipment);
                }
            }
            if (next.isEmpty()) return false;
            EQUIPMENT = next;
            return true;
        } catch (ParserConfigurationException | IOException | SAXException | RuntimeException e) {
            return false;
        }
    }

    public static ArrayList<Equipment> getParseItems() {
        ArrayList<Equipment> list = new ArrayList<>();

        for (Equipment e : EQUIPMENT.values()) {
            if (isParseItem(e)) list.add(e);
        }

        return list;
    }

    public static Boolean isParseItem(Equipment e) {
        if (e == null) return false;
        final boolean isNonConsumable = e.labels != null && !e.labels.contains("CONSUMABLE");
        final boolean isSTUT = e.labels != null && (e.labels.contains("ST") || e.labels.contains("UT"));

        // detecting tiered gear requires:
        //  - tier label
        //  - armor fix (shifted up)
        //  - not st/ut (because some ut/st get the T0 label for some reason)
        final boolean isTieredGear = e.labels != null && (e.labels.contains("T" + e.tier) || (e.labels.contains("ARMOR") && e.labels.contains("T" + (e.tier+1)))) && !isSTUT;

        return (isNonConsumable && (isSTUT || isTieredGear));
    }

    public static Equipment getEquipmentById(int id) {
        return EQUIPMENT.get(id);
    }

    /** Detached catalog suitable for a feed-power picker; callers cannot mutate the shared definitions. */
    public static java.util.Map<Integer, Integer> feedPowerCatalog() {
        java.util.Map<Integer, Integer> values = new java.util.TreeMap<>();
        for (Equipment item : EQUIPMENT.values()) if (item.feedpower > 0) values.put(item.id, item.feedpower);
        return java.util.Collections.unmodifiableMap(values);
    }

    public static class Equipment {
        public int id;
        public String name;
        public int slotType;
        public int tier;
        public String description;
        public int feedpower;
        public String labels;
        public String displayId;

        public String name() {
            if (displayId != null) {
                return displayId;
            } else {
                return name;
            }
        }
    }
}
