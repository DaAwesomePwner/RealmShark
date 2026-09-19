package tomato.backend.data;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class DpsDataTest {
    @Test public void baselineJarRecordingDeserializesAndInfersHistoricalContext() throws Exception {
        assertEquals(8052266513416820004L, ObjectStreamClass.lookup(DpsData.class).getSerialVersionUID());
        DpsData saved;
        try (ObjectInputStream input = new ObjectInputStream(new GZIPInputStream(
            new ByteArrayInputStream(Base64.getDecoder().decode(BASELINE_GZIP))))) {
            saved = (DpsData) input.readObject();
        }
        assertEquals("Baseline encounter", saved.map.name);
        assertEquals(5000, saved.totalDungeonPcTime); assertEquals(123456, saved.dungeonStartTime);
        Entity target = saved.hitList.get(99);
        assertEquals(1000, target.maxHp()); assertEquals(321, target.getDamageList().get(0).damage);
        assertTrue(target.getDamageList().get(0).owner.isUser());
        assertEquals("HistoricLocal", target.getDamageList().get(0).owner.getStatName());
        assertEquals(768, saved.getLocalPlayerContext().classType);
        assertEquals("HistoricGuild", saved.getLocalPlayerContext().guild);
        assertNotNull(saved.debugPackets);

        DpsData exported = roundTrip(saved.getSaveFile(false));
        assertNull(exported.debugPackets);
        assertEquals("HistoricGuild", exported.getLocalPlayerContext().guild);
        assertEquals(321, exported.hitList.get(99).getDamageList().get(0).damage);
    }

    @Test public void explicitContextIsDetachedAndSurvivesExportWithoutLocalDamage() throws Exception {
        Entity local = player(1, 768, "Local", "Original guild", true);
        DpsData saved = new DpsData(new MapInfoPacket(), new HashMap<>(), new ArrayList<>(), 0, 0, new ArrayList<>(), local);
        local.objectType = 775;
        local.stat.get(StatType.GUILD_NAME_STAT).stringStatValue = "Changed guild";
        for (boolean debug : new boolean[]{false, true}) {
            DpsData exported = roundTrip(saved.getSaveFile(debug));
            assertTrue(exported.hitList.isEmpty());
            assertEquals(768, exported.getLocalPlayerContext().classType);
            assertEquals("Original guild", exported.getLocalPlayerContext().guild);
            assertEquals(debug, exported.debugPackets != null);
        }
    }

    @Test public void inferenceSupportsLocalEntityRawHitsAndAggregateOnlyHistory() {
        Entity local = player(1, 768, "Local", "Guild", true);
        Entity target = new Entity(null, 99, 0);
        target.genericDamageHit(local, new Projectile(20), 1000);
        HashMap<Integer, Entity> hits = new HashMap<>(); hits.put(target.id, target);
        assertEquals("Guild", saved(hits).getLocalPlayerContext().guild);
        target.getDamageList().clear();
        assertEquals("Aggregate-only older recordings still identify the local player", "Guild", saved(hits).getLocalPlayerContext().guild);
        hits.clear(); hits.put(local.id, local);
        assertEquals(768, saved(hits).getLocalPlayerContext().classType);
    }

    @Test public void inferenceRejectsConflictingOrUnmarkedLocalPlayers() {
        Entity local = player(1, 768, "Local", "Guild", true);
        Entity other = player(2, 768, "Other", "Guild", true);
        Entity target = new Entity(null, 99, 0);
        target.getDamageList().add(new Damage(local, 1, 10));
        target.getDamageList().add(new Damage(other, 2, 10));
        HashMap<Integer, Entity> hits = new HashMap<>(); hits.put(target.id, target);
        assertNull("Two marked identities are not a reliable context", saved(hits).getLocalPlayerContext());
        target.getDamageList().set(1, new Damage(player(1, 768, "Local", "Different guild", true), 2, 10));
        assertNull("Conflicting snapshots of the same ID are ambiguous", saved(hits).getLocalPlayerContext());
        target.getDamageList().clear(); target.getDamageList().add(new Damage(player(1, 768, "Local", "Guild", false), 1, 10));
        assertNull("A lone contributor is not necessarily the user", saved(hits).getLocalPlayerContext());
    }

    @Test public void partialContextKeepsClassAndDoesNotInventGuild() throws Exception {
        Entity local = player(1, 768, "Local", null, true);
        DpsData saved = new DpsData(null, new HashMap<>(), new ArrayList<>(), 0, 0, null, local);
        DpsData.LocalPlayerContext missing = roundTrip(saved).getLocalPlayerContext();
        assertTrue(missing.hasClass()); assertFalse(missing.hasGuild()); assertNull(missing.guild);
        local.stat.set(StatType.GUILD_NAME_STAT, text(""));
        DpsData.LocalPlayerContext empty = DpsData.LocalPlayerContext.capture(local);
        assertFalse(empty.hasGuild()); assertEquals("", empty.guild);
    }

    private static DpsData saved(HashMap<Integer, Entity> hits) {
        return new DpsData(null, hits, new ArrayList<>(), 0, 0, null);
    }
    private static Entity player(int id, int type, String name, String guild, boolean user) {
        Entity entity = new Entity(null, id, 0); entity.objectType = type;
        entity.stat.set(StatType.NAME_STAT, text(name));
        if (guild != null) entity.stat.set(StatType.GUILD_NAME_STAT, text(guild));
        if (user) entity.setUser(1);
        return entity;
    }
    private static StatData text(String value) { StatData stat = new StatData(); stat.stringStatValue = value; return stat; }
    private static DpsData roundTrip(DpsData saved) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(saved); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (DpsData) input.readObject();
        }
    }

    // Actual ObjectOutputStream bytes generated with ONLY the unmodified baseline JAR on the
    // classpath: build/code-review-20260919/libs/RealmShark-v1.2.3.jar, 2026-09-19.
    // serialver: DpsData = 8052266513416820004L; Entity = -4686692941424283051L.
    // Recipe: user Entity #7/type768, name HistoricLocal, guild HistoricGuild, setUser(11);
    // target #99/type100, maxHP1000, raw Damage(user,1000,321), hit interval 1000..2000;
    // six-argument DpsData(map "Baseline encounter", {99:target}, [], 5000, 123456, []).
    // Gzip+Base64 keeps the old serialized fixture inline and independent of a local build/JAR.
    private static final String BASELINE_GZIP =
        "H4sIAAAAAAAA/8VWTYgcRRR+M/u/O7Ob3XGTkDUhiEa8zOjGQ2AJ5meS3V56kyWzUdg9mJrumplOerp6q6vnJwclB42i4MUIOQREohdzylHBIDnoMeaiCIIEBBFyFBGR+F5Xz9/urLkErUN31XuvXr1676v33q2HMBRImFOiypTIFpl1iXt21maKZfN+kMe/uMO/fPGva88mYXgZdtmhV+bCKygm1ZpT5cswo4Ribl7TVy0imjBjc6YqZ4RySo7FlCO8QMGseZHVWC5Ujps7LiVrmk6gFkxI2bwYllfpbBVswhuQMGGk4ihiK8h07VpiQWWF+bhnoMp8BQdNX+/KOZ4lqo5XziHb8EpCa1to+EAj8eBY9IfMO3jbDCnMksJs24zGlfv7r3/DbgxAwoDBwLnM9db6IH0bkVlD3QQJ0x01sV1DIz9+fXf3he8GIHkaxl3B7NPMUkIaMKYqkgcV4doN/xVtS6o+it9dZF1bmcu8ctbwFC9zOfPg45t/XLl6JEkWDdWYG/KGhF0duTNhtcjl27eu7Z/48Of3kmgUWWyhsn39wnnKU45qfvX7W9e/z8yfT8IeA4atCpOGvQwpS/IoShS9dZiyWbXKKidFiKZIbq/DbIkVJUWS28eVkk4xJGnEQ8mRgcqzKivzNYaHGZB0UH7UCVZd1uRyHYad4HzA5TJMuaxHFI91hVB5KXyNpGi55nBJSwPGRfEit9Ra00deOlAscILYpA0YK7KAIwoV4iq5YZgwbkeaKZYxhFKaou0gWtKEaTpiRRQN+2yNS+nYXMGMBhj5NFfAu3llxNegx6qcNk2ZkPYjFWSoz+1Y07gmLjkaskgZ8AUaM9eGJHk995qQrr0qopdEavEaiOl9po5QLo6QlqXroMwEyZz3kcT1a4hBvGUM/ErRbq1G7vURsVsTv43fRITfxE4g0eGZv/vn7Rs31ftJmFqHDILEs7jrCpmPYJGvljE4lo5EtJiIF+QMRJX2+zqkhWw2Di+GTNqR2JSoe1ye7YrqoIrwtrvOXJfbiyjIvXO85HJ6NYEJQ9EOBU/39ZdG9MIGHkRipzyylACR2dge0paU4dU8VN4kh0yaPTad6YT8qYhcsJiLeykuZK+C/b3B5V5YDXItNoZuduu2V+nZtnNYZE/8vBcIQlLQuY7LKZX1u+JqW2KhBwWJ471IiOKbxvlEh/woHoQOSHSttgKpPQslvaWVLy4cqv12+9NWRhl99JixPTkSYTTOc9DOc6jO35G18x7E6t5+WCX/vnCo9tPnB374AJPkBlYynQ/2bvSGiQSj99fw8YYdbkdNxH34yWeZj46+ea9172/9fx9o12xfTWLy2L2/TyYvJGHI0K+Z4IHpGutA0AKFAan2fK0uTBhtCZIj9iAygwi4bRRFyOxNBVgB00uY8ASmZlMg6h5jMKl4ficFi6Hj2o+58n83tmFKk580tIjzTEjf5yji3R6K8iv8v154gmObQ6mFOdDCb6t9yva0T3v45v25Y79sJmEO6wDmaFHX1XSNu9wXUmEdoCdZlpj/7QKMFMsnhUv9zrQVSsk9dY4zt1qwhOTYENlOCbvB0FVN7BJKvgGTZcy4Z33ucVuX/OEKd8oVVYDJKmvoo6KKb0AaCR1l2FV4osBqnHo1Tg1KCpuret4JqCoHBRgNvUseJuOX1tvTeTQ2nmIKLkCq5nDaoai6YYdVd2xVweprayVdxSAVN72LktktWqsRXhF4K2xZgpg+vZU+vxPj8E6Ml2NGdwsyJunuXTalsX0JsAXTTaDODRImW/HU8Vs6evXA65fvvxtlx0HKUdQsncC30lUitheE/lTATukENl1Y2vhB7sUlP8IWHX/E9/8B4EKAiE0MAAA=";
}
