package packets.packetcapture.logger;

import packets.Packet;
import packets.PacketType;
import packets.incoming.*;
import packets.data.PartyPlayerData;
import packets.outgoing.ForReconnectPacket;
import tomato.realmshark.ParseDungeon;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/** Schema names may be inspected freely; values cross only this explicit allowlist. */
public final class DiscoveryCatalog {
    private static final Map<String, Set<String>> VALUES = new HashMap<>();
    static {
        allow("NEWTICK", "tickId tickTime serverRealTimeMS serverLastTimeRTTMS");
        allow("MAPINFO", "width height difficulty maxPlayerCount viewDistance maxRealmScore currentRealmScore");
        allow("REALM_SCORE_UPDATE", "score");
        allow("REALM_HERO_LEFT_MSG", "realmHeroesLeft");
        allow("FAILURE", "errorId");
        allow("QUEUE_INFORMATION", "currentPosition maxPosition");
        allow("DAMAGE", "targetId damageAmount damageProperties bulletId objectId");
        allow("ENEMYHIT", "time bulletId targetId shooterID kill mainID");
        allow("PLAYERSHOOT", "time bulletId weaponId projectileId angle isBurst patternIdx attackType");
        allow("PLAYERHIT", "bulletId objectId");
        allow("SERVERPLAYERSHOOT", "bulletId ownerId containerType angle damage summonerId bulletType bulletCount anglesBetweenBullets");
        allow("CREATE_SUCCESS", "objectId");
        allow("MOVE", "tickId time");
        allow("GROUNDDAMAGE", "time position.x position.y");
        allow("STATS", "state.hp state.mp state.attack state.defense state.speed state.vitality state.wisdom state.dexterity");
        allow("INVSWAP", "time slotFrom.objectId slotFrom.slotId slotFrom.objectType slotTo.objectId slotTo.slotId slotTo.objectType");
        allow("INVRESULT", "result resultType slotFrom.objectId slotFrom.slotId slotFrom.objectType slotTo.objectId slotTo.slotId slotTo.objectType condition1 condition2");
        allow("ENEMYSHOOT", "ownerId bulletId bulletType damage numShots angle angleInc");
        allow("AOE", "radius damage effect duration armorPiercing origType");
        allow("USEITEM", "time slotObject.objectId slotObject.slotId slotObject.objectType useItemType useItemFlag");
        allow("PING", "serial");
        allow("PONG", "serial time");
        allow("QUESTOBJID", "objectId");
        allow("CLIENTSTAT", "value");
        allow("UPDATE", "levelType");
        allow("EXALTATION_BONUS_CHANGED", "objType attackProgress defenseProgress speedProgress dexterityProgress vitalityProgress wisdomProgress healthProgress manaProgress");
        allow("INCOMING_PARTY_MEMBER_INFO", "partyId maxSize");
        allow("PARTY_MEMBER_ADDED", "playerId classId skinId");
        allow("PARTY_ACTION_RESULT", "playerId actionId");
        allow("PARTY_ACTION", "playerId actionId");
        allow("PARTY_JOIN_REQUEST", "partyId");
        allow("PARTY_REQUEST_RESPONSE", "classId skinId state");
        allow("INCOMING_PARTY_INVITE", "partyId");
        allow("SHOWEFFECT", "effectType presenceMask targetObjectId pos1.x pos1.y pos2.x pos2.y color duration");
        allow("OTHERHIT", "time bulletId objectId targetId");
        allow("STASIS", "entityId stasisDuration");
    }
    private static void allow(String name, String fields) {
        VALUES.put(name, new HashSet<>(Arrays.asList(fields.split(" "))));
    }
    public static String direction(PacketType type) {
        return type == null ? "Unknown" : (INCOMING.contains(type.getIndex()) ? "Server → client" : "Client → server");
    }
    private static final Set<Integer> INCOMING = new HashSet<>(Arrays.asList(PacketType.getPacketTypeByDirection(true)));

    public static Map<String, Object> values(Packet packet, PacketType type) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (type == null) return result;
        for (String path : new TreeSet<>(VALUES.getOrDefault(type.name(), Collections.emptySet()))) {
            try {
                Object value = packet;
                for (String part : path.split("\\.")) {
                    if (value == null) break;
                    value = value.getClass().getField(part).get(value);
                }
                if (value instanceof Number) {
                    if (Double.isFinite(((Number)value).doubleValue())) result.put(path, value);
                } else if (value instanceof Boolean) result.put(path, value);
                else if (value instanceof Enum<?>) result.put(path, ((Enum<?>)value).name());
            } catch (ReflectiveOperationException ignored) { /* A removed field has no sample. */ }
        }
        if (packet instanceof MapInfoPacket)
            result.put("canonicalMap", ParseDungeon.canonicalMapName((MapInfoPacket)packet));
        if (packet instanceof ForReconnectPacket) {
            String encoded = ((ForReconnectPacket)packet).reconnectInfo;
            int count = 0;
            if (encoded != null) for (String part : encoded.split(":", -1)) if (!part.isEmpty()) count++;
            result.put("serverNameCount", count);
        }
        if (packet instanceof IncomingPartyMemberInfoPacket) {
            PartyPlayerData[] players = ((IncomingPartyMemberInfoPacket)packet).partyPlayers;
            List<Map<String, Integer>> roster = new ArrayList<>();
            if (players != null) for (int i = 0; i < Math.min(players.length, 100); i++) {
                PartyPlayerData p = players[i]; if (p == null) continue;
                Map<String, Integer> member = new LinkedHashMap<>();
                member.put("memberId", Short.toUnsignedInt(p.id));
                member.put("objectFieldUnverified", Short.toUnsignedInt(p.objectId));
                roster.add(member);
            }
            result.put("members", roster);
            result.put("memberCount", players == null ? 0 : players.length);
            result.put("rosterTruncated", players != null && players.length > 100);
        }
        if (packet instanceof ShowEffectPacket) {
            int mask = ((ShowEffectPacket)packet).presenceMask;
            String[] optional = {"color", "pos1.x", "pos1.y", "pos2.x", "pos2.y", "duration", "targetObjectId"};
            for (int i = 0; i < optional.length; i++) if ((mask & (1 << i)) == 0) result.remove(optional[i]);
        }
        return result;
    }

    /** Rare state changes and parser failures must survive normal event sampling. */
    static boolean important(PacketType type) {
        if (type == null) return false;
        String name = type.name();
        return name.equals("FAILURE") || name.equals("RECONNECT") || name.equals("HELLO")
            || name.equals("QUEUE_INFORMATION") || name.equals("FOR_RECONNECT") || name.equals("LOAD") || name.equals("ESCAPE")
            || name.equals("MAPINFO") || name.equals("CREATE_SUCCESS") || name.equals("EXALTATION_BONUS_CHANGED")
            || name.equals("INCOMING_PARTY_MEMBER_INFO") || name.equals("PARTY_MEMBER_ADDED")
            || name.equals("PARTY_ACTION_RESULT") || name.equals("PARTY_ACTION") || name.equals("PARTY_JOIN_REQUEST")
            || name.equals("PARTY_REQUEST_RESPONSE") || name.equals("INCOMING_PARTY_INVITE")
            || name.equals("USEITEM") || name.equals("INVSWAP") || name.equals("INVRESULT");
    }

    public static final class SchemaField {
        public final String packet, path, type, retention;
        SchemaField(String packet, String path, String type, String retention) {
            this.packet = packet; this.path = path; this.type = type; this.retention = retention;
        }
    }
    public static List<SchemaField> fields() {
        List<SchemaField> result = new ArrayList<>();
        for (PacketType type : PacketType.values()) schema(result, type.name(), type.getPacketClass(), "", 0);
        result.add(new SchemaField("MAPINFO", "canonicalMap", "String", "Derived: exact local catalog label; arbitrary map text withheld"));
        result.add(new SchemaField("FOR_RECONNECT", "serverNameCount", "Number", "Derived from the candidate list; server names and reconnect data withheld"));
        result.add(new SchemaField("INCOMING_PARTY_MEMBER_INFO", "members", "List", "Derived: up to 100 numeric member IDs and unverified object fields; names withheld"));
        result.add(new SchemaField("INCOMING_PARTY_MEMBER_INFO", "memberCount / rosterTruncated", "Number / boolean", "Derived from roster length"));
        return Collections.unmodifiableList(result);
    }
    private static void schema(List<SchemaField> out, String packet, Class<?> clazz, String prefix, int depth) {
        for (Field field : clazz.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String path = prefix + field.getName();
            Class<?> type = field.getType();
            String retention = VALUES.getOrDefault(packet, Collections.emptySet()).contains(path)
                ? "Selected gameplay value" : "Schema only; value withheld";
            if (type.getSimpleName().equals("StatData")) retention = "See Stat explorer; selected numeric stats only";
            out.add(new SchemaField(packet, path, type.getSimpleName(), retention));
            Class<?> child = type.isArray() ? type.getComponentType() : type;
            if (depth < 3 && child.getName().startsWith("packets.data.") && !child.isEnum())
                schema(out, packet, child, path + (type.isArray() ? "[]." : "."), depth + 1);
        }
    }

    /** Reviewed opportunities, not assertions that the packets have been seen on this client build. */
    public static final String[][] OPPORTUNITIES = {
        {"Run / party history", "MAPINFO, INCOMING_PARTY_MEMBER_INFO, PARTY_MEMBER_ADDED, PARTY_ACTION_RESULT", "See Runs and Timeline for observed visits and numeric party rosters", "Visits are not clears. Party member IDs are not yet verified world-player identities."},
        {"Exalt progress history", "EXALTATION_BONUS_CHANGED", "Eight progress values, class and observed changes in Timeline", "A snapshot is not an award. Cross-visit changes remain unassigned; identity must be re-observed after gaps."},
        {"Realm progress", "REALM_SCORE_UPDATE, REALM_HERO_LEFT_MSG, MAPINFO", "Score and remaining heroes", "Compare a fresh sample against the in-game Realm display."},
        {"Connection timing", "NEWTICK, PING, PONG", "Tick duration and server-reported RTT field", "Tick duration is not client FPS. Validate RTT units before using it as latency."},
        {"Queue history", "QUEUE_INFORMATION", "Position and queue capacity over time", "Observe a normal queued connection; no admission or queue bypass inference."},
        {"Party re-entry trace", "PARTY_JOIN_REQUEST, PARTY_REQUEST_RESPONSE, PARTY_ACTION, PARTY_ACTION_RESULT, FOR_RECONNECT, RECONNECT, HELLO, QUEUE_INFORMATION, FAILURE, MAPINFO, LOAD, CREATE_SUCCESS", "Ordered passive evidence in Logging > Re-entry trace", "The sequence does not prove a bypass. Realm admission remains server-controlled, and missing traffic cannot be reconstructed."},
        {"Ability / consumable detail", "USEITEM", "Item type, slot, use mode and flag", "A client use request does not prove server acceptance or successful healing."},
        {"Buffs and resource changes", "UPDATE, NEWTICK", "HP, MP, conditions, boosts, timers and breath", "Track per object and area; HP rises alone cannot identify healer or healing amount."},
        {"Enemy attack patterns", "ENEMYSHOOT, AOE, DAMAGE", "Projectile type, volley size, AOE radius / effect", "Shots and damage packets are different evidence; sampled logs are not full combat totals."},
        {"Combat attribution", "CREATE_SUCCESS, PLAYERSHOOT, SERVERPLAYERSHOOT, ENEMYHIT, PLAYERHIT, DAMAGE", "Local object, projectile, shooter, target and summoner IDs", "Use detail mode for short traces. Client hit reports are not authoritative damage amounts."},
        {"Inventory actions", "INVSWAP, INVRESULT", "Source / destination items, slots and result fields", "Distinguish client requests from server result observations; confirm result semantics in game."},
        {"Pet / progression detail", "UPDATE, NEWTICK, STATS", "Pet abilities, exalts, projectile multipliers, forge fire", "Only nearby / transmitted stats exist; account-wide completeness is not implied."},
        {"Unmapped protocol", "UNKNOWN147, UNKNOWN164, UNKNOWN165, UNKNOWN181, UNKNOWN190, DAMAGE_BOOST", "IDs, frequency, size and decoder field names", "Opaque bytes and unknown values are withheld; names do not establish meaning."}
    };
    private DiscoveryCatalog() {}
}
