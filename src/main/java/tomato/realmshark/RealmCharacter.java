package tomato.realmshark;

import org.xml.sax.SAXException;
import tomato.realmshark.enums.CharacterClass;
import util.StringXML;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Basic data class to store Character info.
 */
public class RealmCharacter {
    /** Explicitly supplied fields, including zero/false. Never inferred from primitive defaults. */
    public final java.util.Map<String, tomato.backend.data.FieldCapture> presence = new java.util.HashMap<>();
    public long receivedAt;
    private static final java.util.concurrent.atomic.AtomicLong ROSTERS = new java.util.concurrent.atomic.AtomicLong();
    public long rosterRevision = ROSTERS.incrementAndGet();
    public void supplied(String field, long at, String source) {
        presence.put(field, new tomato.backend.data.FieldCapture(at, source));
    }
    public void supplied(String field) { supplied(field, System.currentTimeMillis(), "Character list"); }
    // Dex 0
    // Spd 1
    // Vit 2
    // Wis 3
    // Def 4
    // Atk 5
    // Mana 6
    // Life 7
    public static TreeMap<Integer, int[]> exalts = new TreeMap<>();

    public int charId;
    public short classNum;
    public String classString;
    public int level;
    public int skin;
    public long exp;
    public long fame;
    public boolean seasonal;
    public boolean backpack;
    public boolean qs3;

    public int[] equipment;
    public String[] equipQS;
    public String date;

    /** Bits for base stats actually present in a character-list response. */
    public int capturedStatMask;
    public int hp;
    public int mp;
    public int atk;
    public int def;
    public int spd;
    public int dex;
    public int vit;
    public int wis;
    public String pcStats;
    public RealmCharacterStats charStats;

    public String petName;
    public String petCreatedOn;
    public int petSkin;
    public int petType;
    public int petInstanceId;
    public int petMaxAbilityPower;
    public int petRarity;
    public int[] petAbilitys;

    /**
     * Returns the player exalt loot drop bonus
     *
     * @param classId Class to receive exalt bonus.
     */
    public static int exaltLootBonus(int classId) {
        if (fullyExalted()) {
            return 35;
        }
        int bonus = 25;
        for (int c : CharacterClass.weaponClasses(classId)) {
            int[] ints = exalts.get(c);
            if (ints == null) return 0;

            for (int i : ints) {
                if (i < 5) {
                    return 0;
                } else if (i < 15 && bonus > 5) {
                    bonus = 5;
                } else if (i < 30 && bonus > 10) {
                    bonus = 10;
                } else if (i < 50 && bonus > 15) {
                    bonus = 15;
                } else if (i < 75 && bonus > 20) {
                    bonus = 20;
                }
            }
        }
        return bonus;
    }

    /**
     * Checks if account is fully exalted.
     *
     * @return True if account is fully exalted.
     */
    private static boolean fullyExalted() {
        if (exalts.size() < 19) return false;

        for (int[] e : exalts.values()) {
            for (int i : e) {
                if (i < 75) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Set character stats
     */
    public void updateCharStats(RealmCharacterStats c) {
        charStats = c;
    }

    /**
     * Simple setter for the class string from the class id.
     */
    public void setClassString() {
        classString = CharacterClass.getName(classNum);
    }

    /**
     * Decodes psStats into charStats
     */
    public void setCharacterStats() {
        charStats = new RealmCharacterStats();
        try {
            charStats.decode(pcStats);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Converts XML data to array of Character data.
     *
     * @param r XML string to be parsed.
     * @return List of Character data parsed from the XML string.
     */
    public static ArrayList<RealmCharacter> getCharList(String r) {
        if (r == null || r.trim().isEmpty()) return null;
//        prettyXML(r);

        StringXML base;
        ArrayList<RealmCharacter> listChars = new ArrayList<>();

        try {
            base = StringXML.getParsedXML(r);
        } catch (ParserConfigurationException | IOException | SAXException e) {
            e.printStackTrace();
            return null;
        }

        for (StringXML xml : base) {
            if (Objects.equals(xml.name, "Char")) {
                RealmCharacter character = new RealmCharacter();
                character.receivedAt = System.currentTimeMillis();
                for (StringXML info : xml) {
                    if (Objects.equals(info.name, "id")) {
                        character.charId = Integer.parseInt(info.value);
                    }

                    for (StringXML v : info) {
                        switch (info.name) {
                            case "ObjectType":
                                character.supplied("class");
                                character.classNum = Short.parseShort(v.value);
                                character.setClassString();
                                break;
                            case "Equipment":
                                character.equipment = Arrays.stream(v.value.split(",")).mapToInt(s -> Integer.parseInt(s.split("#")[0])).toArray();
                                for (int slot = 0; slot < Math.min(28, character.equipment.length); slot++) character.supplied("equipment." + slot);
                                break;
                            case "EquipQS":
                                character.equipQS = v.value.split(",");
                                break;
                            case "Level":
                                character.supplied("level");
                                character.level = Integer.parseInt(v.value);
                                break;
                            case "Texture":
                                character.supplied("skin");
                                character.skin = Integer.parseInt(v.value);
                                break;
                            case "CreationDate":
                                character.supplied("created");
                                character.date = v.value;
                                break;
                            case "HasBackpack":
                                character.backpack = v.value.equals("1");
                                break;
                            case "Has3Quickslots":
                                character.qs3 = v.value.equals("1");
                                break;
                            case "MaxHitPoints":
                                character.supplied("stat.0");
                                character.hp = Integer.parseInt(v.value);
                                character.capturedStatMask |= 1;
                                break;
                            case "MaxMagicPoints":
                                character.supplied("stat.1");
                                character.mp = Integer.parseInt(v.value);
                                character.capturedStatMask |= 2;
                                break;
                            case "Attack":
                                character.supplied("stat.2");
                                character.atk = Integer.parseInt(v.value);
                                character.capturedStatMask |= 4;
                                break;
                            case "Defense":
                                character.supplied("stat.3");
                                character.def = Integer.parseInt(v.value);
                                character.capturedStatMask |= 8;
                                break;
                            case "Speed":
                                character.supplied("stat.4");
                                character.spd = Integer.parseInt(v.value);
                                character.capturedStatMask |= 16;
                                break;
                            case "Dexterity":
                                character.supplied("stat.5");
                                character.dex = Integer.parseInt(v.value);
                                character.capturedStatMask |= 32;
                                break;
                            case "HpRegen":
                                character.supplied("stat.6");
                                character.vit = Integer.parseInt(v.value);
                                character.capturedStatMask |= 64;
                                break;
                            case "MpRegen":
                                character.supplied("stat.7");
                                character.wis = Integer.parseInt(v.value);
                                character.capturedStatMask |= 128;
                                break;
                            case "Seasonal":
                                if ("True".equalsIgnoreCase(v.value) || "False".equalsIgnoreCase(v.value)) {
                                    character.supplied("seasonal"); character.seasonal = Boolean.parseBoolean(v.value);
                                }
                                break;
                            case "Exp":
                                character.exp = Long.parseLong(v.value);
                                break;
                            case "CurrentFame":
                                character.supplied("fame");
                                character.fame = Long.parseLong(v.value);
                                break;
                            case "PCStats":
                                character.pcStats = v.value;
                                character.setCharacterStats();
                                break;
                            case "Pet":
                                for (StringXML pet : info) {
                                    switch (pet.name) {
                                        case "createdOn":
                                            character.petCreatedOn = pet.value;
                                            break;
                                        case "instanceId":
                                            character.supplied("pet.81");
                                            character.petInstanceId = Integer.parseInt(pet.value);
                                            break;
                                        case "maxAbilityPower":
                                            character.supplied("pet.85");
                                            character.petMaxAbilityPower = Integer.parseInt(pet.value);
                                            break;
                                        case "name":
                                            character.supplied("pet.82");
                                            character.petName = pet.value;
                                            break;
                                        case "rarity":
                                            character.supplied("pet.84");
                                            character.petRarity = Integer.parseInt(pet.value);
                                            break;
                                        case "skin":
                                            character.supplied("pet." + packets.data.enums.StatType.SKIN_ID.get());
                                            character.petSkin = Integer.parseInt(pet.value);
                                            break;
                                        case "type":
                                            character.supplied("pet.83");
                                            character.petType = Integer.parseInt(pet.value);
                                            break;
                                        case "Abilities":
                                            int[] a = new int[9];
                                            int i = 0;
                                            for (StringXML abilitys : pet) {
                                                if (!"Ability".equals(abilitys.name) || i >= 3) continue;
                                                for (StringXML ability : abilitys) {
                                                    int component = "points".equals(ability.name) ? 0 : "power".equals(ability.name) ? 1 : "type".equals(ability.name) ? 2 : -1;
                                                    if (component >= 0) {
                                                        a[i * 3 + component] = Integer.parseInt(ability.value);
                                                        character.supplied("pet." + (87 + component * 3 + i));
                                                    }
                                                }
                                                i++;
                                            }
                                            character.petAbilitys = a;
                                    }
                                }
                                break;
                        }
                    }
                    checkExalt(info);
                }
                listChars.add(character);
            }
//            checkExalt(xml);
        }
        return listChars;
    }

    private static void checkExalt(StringXML xml) {
        if (Objects.equals(xml.name, "PowerUpStats")) {
            for (StringXML info : xml) {
                if (Objects.equals(info.name, "ClassStats")) {
                    int clazz = 0;
                    int[] exalts = null;
                    for (StringXML c : info) {
                        if (Objects.equals(c.name, "class")) {
                            clazz = Integer.parseInt(c.value);
                        } else if (Objects.equals(c.name, "#text")) {
                            exalts = Arrays.stream(c.value.split(",")).mapToInt(Integer::parseInt).toArray();
                        }
                    }
                    RealmCharacter.exalts.put(clazz, exalts);
                }
            }
        }
    }

    /**
     * Pretty prints XML
     *
     * @param input Ugly XML text
     */
    private static void prettyXML(String input) {
        try {
            Source xmlInput = new StreamSource(new StringReader(input));
            StringWriter stringWriter = new StringWriter();
            StreamResult xmlOutput = new StreamResult(stringWriter);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute("indent-number", 4);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(xmlInput, xmlOutput);
            String out = xmlOutput.getWriter().toString();
            System.out.println(out);
        } catch (Exception e) {
            throw new RuntimeException(e); // simple exception handling, please review it
        }
    }

    public static boolean checkExaltNew(String r) {
        if (r == null || r.trim().isEmpty()) return false;
        StringXML base;

        try {
            base = StringXML.getParsedXML(r);
        } catch (ParserConfigurationException | IOException | SAXException e) {
            e.printStackTrace();
            return false;
        }
        if (!Objects.equals(base.name, "AccountPowerups")) return false;
        if (Objects.equals(base.name, "AccountPowerups")) {
            for (StringXML info : base) {
                if (Objects.equals(info.name, "ClassPowerup")) {
                    int clazz = 0;
                    int[] exalts = new int[8];
                    for (StringXML c : info) {
                        if (Objects.equals(c.name, "Class")) {
                            clazz = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Dex")) {
                            exalts[0] = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Spd")) {
                            exalts[1] = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Vit")) {
                            exalts[2] = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Wis")) {
                            exalts[3] = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Def")) {
                            exalts[4] = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Att")) {
                            exalts[5] = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Mana")) {
                            exalts[6] = Integer.parseInt(c.children.get(0).value);
                        } else if (Objects.equals(c.name, "Life")) {
                            exalts[7] = Integer.parseInt(c.children.get(0).value);
                        }
                    }
                    RealmCharacter.exalts.put(clazz, exalts);
                }
            }
        }

        return true;
    }

    @Override
    public String toString() {
        return "RealmCharacter{" +
                "\n   charId=" + charId +
                "\n   classNum=" + classNum +
                "\n   classString=" + classString +
                "\n   level=" + level +
                "\n   skin=" + skin +
                "\n   exp=" + exp +
                "\n   fame=" + fame +
                "\n   seasonal=" + seasonal +
                "\n   backpack=" + backpack +
                "\n   qs3=" + qs3 +
                "\n   equipment=" + Arrays.toString(equipment) +
                "\n   equipQS=" + Arrays.toString(equipQS) +
                "\n   date=" + date +
                "\n   hp=" + hp +
                "\n   mp=" + mp +
                "\n   atk=" + atk +
                "\n   def=" + def +
                "\n   spd=" + spd +
                "\n   dex=" + dex +
                "\n   vit=" + vit +
                "\n   wis=" + wis +
                "\n   pcStats=" + pcStats +
                "\n   charStats=" + charStats +
                "\n   petName=" + petName +
                "\n   petCreatedOn=" + petCreatedOn +
                "\n   petSkin=" + petSkin +
                "\n   petType=" + petType +
                "\n   petInstanceId=" + petInstanceId +
                "\n   petMaxAbilityPower=" + petMaxAbilityPower +
                "\n   petRarity=" + petRarity +
                "\n   petAbilitys=" + Arrays.toString(petAbilitys);
    }
}
