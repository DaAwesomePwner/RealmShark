package tomato.gui.character;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.Stat;
import tomato.realmshark.RealmCharacter;

/** Synthetic pets for the Pets gallery tests: journal records, Pet Yard objects and a pets.xml naming two families. */
final class PetFixtures {
    static final String ACCOUNT = CharacterJournal.accountKey("pet-fixture-account");
    static final String OTHER = CharacterJournal.accountKey("pet-fixture-other");
    static final long NOW = 1_700_000_000_000L;
    static final int WIZARD = 782, KNIGHT = 798, PRIEST = 784;
    /** Pet type objects named in {@link #defs}: a Canine and a Feline. */
    static final int HOUND = 0x7a01, CAT = 0x7a02;
    static final int HEAL = 407, MAGIC_HEAL = 408, ELECTRIC = 406;

    private PetFixtures() {}

    /** A known pet: slot values in order, -1 = unknown. */
    static CharacterJournal.PetRecord pet(Long instanceId, String name, Integer type, Integer rarity, Integer max, long observedAt,
                                          int[] types, int[] levels, int[] points) {
        CharacterJournal.PetRecord pet = new CharacterJournal.PetRecord();
        pet.instanceId = instanceId; pet.name = name; pet.type = type; pet.rarity = rarity; pet.maxAbilityPower = max;
        pet.skin = 0x7b01; pet.abilityType = types.clone(); pet.abilityLevel = levels.clone(); pet.abilityPoints = points.clone();
        pet.observedAt = observedAt; pet.source = "Character list";
        return pet;
    }

    /** "Rex": a Rare Canine with max level 70, Heal 45, Magic heal 30 and a locked Electric slot. */
    static CharacterJournal.PetRecord rex(long observedAt) {
        return pet(9001L, "Rex", HOUND, 2, 70, observedAt, new int[]{HEAL, MAGIC_HEAL, ELECTRIC}, new int[]{45, 30, 1}, new int[]{1000, 200, 0});
    }

    /** The character list said this character has no pet. */
    static CharacterJournal.PetRecord none(long observedAt) {
        CharacterJournal.PetRecord pet = new CharacterJournal.PetRecord();
        pet.absent = Boolean.TRUE; pet.observedAt = observedAt; pet.source = "Character list";
        return pet;
    }

    static CharacterJournal.CharacterRecord character(String account, int id, int classId, String className, boolean dead,
                                                      CharacterJournal.PetRecord pet) {
        CharacterJournal.CharacterRecord r = new CharacterJournal.CharacterRecord();
        r.account = account; r.characterId = id; r.key = account + ":" + id; r.classId = classId; r.className = className;
        r.dead = dead; r.pet = pet; r.lastSeen = NOW;
        return r;
    }

    static PetGalleryModel.YardPet yard(int objectId, CharacterJournal.PetRecord pet) {
        pet.source = "Pet Yard capture";
        return new PetGalleryModel.YardPet(objectId, pet);
    }

    /** pets.xml under a temporary asset root, read through the public loader (parse is package-private). */
    static PetDefinitions defs(Path root) throws Exception {
        Files.createDirectories(root.resolve("xml"));
        Files.write(root.resolve("xml/pets.xml"), ("<Objects><Object type='0x7a01' id='Fixture Hound'><Family>Canine</Family></Object>"
            + "<Object type='31234' id='Fixture Cat'><Family>Feline</Family></Object></Objects>").getBytes(StandardCharsets.UTF_8));
        return PetDefinitions.read(root);
    }

    static StatData stat(StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; return stat;
    }
    static StatData text(StatType type, String value) {
        StatData stat = stat(type, 0); stat.stringStatValue = value; return stat;
    }

    /** Publishes a Pet Yard object (or, with a negative object id, equipped-character metadata) into the current scope. */
    static void publish(ProgressionData source, int objectId, long at, String from, StatData... stats) {
        source.pet(source.scope(), objectId, new Stat(stats), at, from, Collections.emptyMap());
    }

    /**
     * A character-list entry whose pet the journal saves (as the metadata parser marks it supplied). {@code abilities}: points,
     * level and type for each slot in order.
     */
    static RealmCharacter listed(int id, int classId, String className, long at, int instanceId, String name, int type, int rarity,
                                 int max, int[] abilities) {
        RealmCharacter c = new RealmCharacter();
        c.charId = id; c.receivedAt = at; c.classNum = (short) classId; c.classString = className; c.supplied("class", at, "Character list");
        c.petInstanceId = instanceId; c.petName = name; c.petType = type; c.petRarity = rarity; c.petMaxAbilityPower = max; c.petSkin = 0x7b01;
        for (int field : new int[]{81, 82, 83, 84, 85, 25, 87, 88, 89, 90, 91, 92, 93, 94, 95}) c.supplied("pet." + field, at, "Character list");
        c.petAbilitys = abilities.clone();
        return c;
    }
}
