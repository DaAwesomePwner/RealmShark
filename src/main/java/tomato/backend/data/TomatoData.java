package tomato.backend.data;

import assets.IdToAsset;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import packets.Packet;
import packets.data.ObjectData;
import packets.data.StatData;
import packets.data.WorldPosData;
import packets.data.enums.NotificationEffectType;
import packets.data.enums.StatType;
import packets.incoming.*;
import packets.outgoing.*;
import tomato.backend.SecurityAbilityUseCheck;
import tomato.gui.chat.ChatGUI;
import tomato.gui.dps.DpsGUI;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.stats.FameTablePanel;
import tomato.gui.stats.LootGUI;
import tomato.realmshark.HttpCharListRequest;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.RealmCharacterStats;
import tomato.realmshark.Sound;
import tomato.realmshark.enums.CharacterClass;
import tomato.realmshark.enums.LootBags;
import util.PropertiesManager;
import util.RNG;

/**
 * Main data class storing all incoming packet data regarding an instance the user is in.
 * Resets the data after leaving the instance.
 */
public class TomatoData {

    private String token;
    public MapInfoPacket map;
    protected int worldPlayerId = -1;
    protected int charId;
    public long time;
    public long timePc;
    private long timePcFirst;
    public Entity player;
    public Entity pet;
    protected final int[][] mapTiles = new int[2048][2048];
    public final HashMap<Integer, Entity> entityList = new HashMap<>();
    protected final HashMap<Integer, Entity> playerList = new HashMap<>();
    public final HashMap<Integer, Entity> playerListUpdated = new HashMap<>();
    protected final Projectile[] projectiles = new Projectile[512];
    // Map keyed by (ownerId << 32) | (bulletId & 0xffffffffL) for reliable lookup of player/server-created projectiles
    protected final HashMap<Long, Projectile> playerProjectiles =
        new HashMap<>();
    protected RNG rng;
    protected HashSet<Integer> crystalTracker = new HashSet<>();
    private HashMap<Integer, Entity> entityHitList = new HashMap<>();
    public VaultData regularVault = new VaultData();
    public VaultData seasonalVault = new VaultData();
    public boolean vaultDataRecievedSeasonal, vaultDataRecievedRegular, characterDataRecieved;
    public ArrayList<RealmCharacter> chars;
    public HashMap<Integer, RealmCharacter> charMap;
    private CharacterJournal characterJournal;
    private String journalAccount;
    private ArrayList<RealmCharacter> journalPendingRoster;
    // These generations and all model application are capture-thread owned. Only the
    // bounded mailbox below crosses into the HTTP worker.
    private long metadataConnection, metadataAccount, metadataSequence, rosterRequest, exaltRequest;
    private boolean metadataAwaitingCreate;
    private final MetadataWorker metadataWorker;
    private volatile MyInfoIdentity myInfoIdentity = new MyInfoIdentity(0, null, -1, -1);
    private Entity myInfoOwner;
    private MyInfoIdentity petIdentity;
    private Entity petOwner;
    private PetAvailability petAvailability = PetAvailability.UNKNOWN;
    private final ProgressionData progression = new ProgressionData();
    public ProgressionData progression() { return progression; }
    public void captureStopped() { progression.captureStopped(); }
    public void captureStarted() { progression.captureStarted(); }
    public void captureBoundary() { progression.reset(null, "Connection changed; waiting for verified account"); }
    public void quests(QuestFetchResponsePacket packet) {
        ProgressionData.Scope origin = progression.scope();
        progression.quests(origin, packet.quests, System.currentTimeMillis());
    }

    public enum PetAvailability { UNKNOWN, ABSENT, PRESENT }

    /** Identity is a generation token, never persisted in an Entity or DPS archive. */
    public static final class MyInfoIdentity {
        public final long generation;
        public final String account;
        public final int characterId, worldPlayerId;
        private MyInfoIdentity(long generation, String account, int characterId, int worldPlayerId) {
            this.generation = generation; this.account = account;
            this.characterId = characterId; this.worldPlayerId = worldPlayerId;
        }
    }

    public MyInfoIdentity myInfoIdentity() { return myInfoIdentity; }

    /** Rechecked around detaching a publication, so neither a changed owner nor a replaced pet can slip through. */
    public boolean isCurrentMyInfoSnapshot(MyInfoIdentity identity, Entity owner, Entity companion, PetAvailability availability) {
        if (identity != myInfoIdentity) return false;
        if (owner == null) return companion == null && availability == PetAvailability.UNKNOWN;
        if (metadataAwaitingCreate || owner != player || owner != myInfoOwner || owner.id != identity.worldPlayerId) return false;
        if (availability == PetAvailability.UNKNOWN) return companion == null;
        return identity.account != null && petIdentity == identity && petOwner == owner
            && companion == pet && availability == petAvailability;
    }

    private void resetMyInfo(String account, int characterId, int objectId) {
        progression.reset(account, "Capture identity changed; previous quest list is stale");
        myInfoOwner = null;
        pet = null; petOwner = null; petIdentity = null; petAvailability = PetAvailability.UNKNOWN;
        myInfoIdentity = new MyInfoIdentity(myInfoIdentity.generation + 1, account, characterId, objectId);
        MyInfoGUI.updateSnapshot(this, myInfoIdentity, null, null, PetAvailability.UNKNOWN);
    }

    /** Capture-thread entry point, including the legacy callback from Entity.updateStats(). */
    public void publishMyInfoPlayer(Entity value) {
        if (value == null || value != player || metadataAwaitingCreate || worldPlayerId < 0 || value.id != worldPlayerId) return;
        StatData stat = value.stat.get(StatType.ACCOUNT_ID_STAT);
        String account = stat == null || stat.stringStatValue == null || stat.stringStatValue.trim().isEmpty()
            ? null : CharacterJournal.accountKey(stat.stringStatValue);
        MyInfoIdentity identity = myInfoIdentity;
        if (!Objects.equals(account, identity.account) || charId != identity.characterId
            || worldPlayerId != identity.worldPlayerId || (myInfoOwner != null && myInfoOwner != value)) {
            resetMyInfo(account, charId, worldPlayerId);
            identity = myInfoIdentity;
        }
        myInfoOwner = value;
        boolean ownedPet = identity.account != null && petIdentity == identity && petOwner == value;
        MyInfoGUI.updateSnapshot(this, identity, value, ownedPet ? pet : null,
            ownedPet ? petAvailability : PetAvailability.UNKNOWN);
    }

    /** Unscoped/stale pet callbacks may only republish the currently bound owner/pet pair. */
    public void publishMyInfoPet(Entity value) {
        if (value == null || value != pet || petOwner != player || petIdentity != myInfoIdentity) return;
        publishMyInfoPlayer(player);
    }

    public TomatoData() { this(HttpCharListRequest::webRequest); }

    @FunctionalInterface
    interface MetadataClient { String request(String token, String endpoint) throws IOException; }

    TomatoData(MetadataClient client) { metadataWorker = new MetadataWorker(client); }

    public synchronized CharacterJournal characterJournal() {
        if (characterJournal == null) {
            characterJournal = new CharacterJournal(java.nio.file.Paths.get("Characters", "journal.json"));
            if (!tomato.Tomato.isPreview()) characterJournal.startSaving();
        }
        return characterJournal;
    }

    /** Called only for the authenticated local player, never nearby players. */
    public void rememberCharacter() {
        if (!progression.scope().accepting) return;
        if (player == null || metadataAwaitingCreate || (metadataConnection > 0 && player.id != worldPlayerId)) return;
        StatData identity = player.stat.get(StatType.ACCOUNT_ID_STAT);
        if (identity == null || identity.stringStatValue == null || identity.stringStatValue.trim().isEmpty()) return;
        String observedAccount = CharacterJournal.accountKey(identity.stringStatValue);
        if (journalAccount != null && !journalAccount.equals(observedAccount)) {
            // Capture changed accounts without a HELLO: the retained credential still
            // belongs to the previous account. Only a new HELLO may supply a credential.
            token = null;
            resetAccountMetadata();
            journalAccount = observedAccount;
        }
        String account = characterJournal().observe(player, charId);
        if (account == null) return;
        journalAccount = account;
        publishMyInfoPlayer(player);
        applyMetadataResponses();
        if (journalPendingRoster != null) {
            characterJournal().mergeRoster(account, journalPendingRoster);
            journalPendingRoster = null;
        }
        characterJournal().exalts(account, RealmCharacter.exalts);
    }
    public ArrayList<DpsData> dpsData = new ArrayList<>();
    protected ArrayList<NotificationPacket> deathNotifications =
        new ArrayList<>();
    protected final HashMap<Integer, Entity> dropList = new HashMap<>();
    private ArrayList<Packet> dpsPacketLog = new ArrayList<>();
    private boolean petyard;
    private RealmCharacterStats currentCharacterStats;
    private final TreeSet<Integer> lootBags = new TreeSet<>();
    private int lootTickToggle = 0;
    private final ArrayList<Entity>[] lootTickContainer = new ArrayList[] {
        new ArrayList<>(),
        new ArrayList<>(),
    };
    private final ArrayList<Entity> killedEntitys = new ArrayList<>();

    protected final HashMap<Long, Projectile> enemyProjectiles =
        new HashMap<>();
    private final DungeonStatData dungeonStatData = new DungeonStatData();
    private int moonlightFlames = 0;
    private static final int MOONLIGHT_BOSS_FLAME_ID = 20518;
    private boolean updatedExaltStats = false;
    private final HashMap<String, ArrayList<String>> propLists =
        new HashMap<>();

    // Track minion/summon to owner mapping for damage attribution
    // Key: minion/summon objectId, Value: owner/player objectId
    private final HashMap<Integer, Integer> minionOwnerMap = new HashMap<>();

    /**
     * Sets the current realm.
     *
     * @param map New realm to be set.
     */
    public void setNewRealm(MapInfoPacket map) {
        tomato.realmshark.RealmEventAlerts.INSTANCE.resetCooldowns();
        clear();
        ParsePanelGUI.clear();
        petYardCheck(map.displayName);
        this.map = map;
        rng = new RNG(map.seed);
    }

    /**
     * Sets the current realm users character id.
     *
     * @param objectId ID of the object in the world.
     * @param charId   Current character id loaded.
     * @param str
     */
    public void setUserId(int objectId, int charId, String str) {
        metadataAwaitingCreate = false;
        invalidateRosterRequest();
        // CREATE establishes a new character even when the server reuses an object ID.
        if (player != null) {
            entityList.remove(player.id); playerList.remove(player.id); playerListUpdated.remove(player.id);
        }
        entityList.remove(objectId);
        player = null;
        this.worldPlayerId = objectId;
        this.charId = charId;
        resetMyInfo(null, charId, objectId);
        updateDungeonStats(charId, str);
        packets.packetcapture.logger.DiscoveryLog.INSTANCE.completionStats(charId, currentCharacterStats.completionCounts());
    }

    public void petYardCheck(String displayName) {
        if (displayName.equals("Pet Yard")) {
            petyard = true;
            progression.clearPets();
        }
    }

    public void webRequest() {
        if (map == null) return;
        if (map.displayName.equals("Pet Yard")) {
            petyard = true;
            progression.clearPets();
            charListHttpRequest();
        } else if (map.displayName.equals("Daily Quest Room")) {
            charListHttpRequest();
        }
    }

    private void updateDungeonStats(int charId, String str) {
        if (
            currentCharacterStats == null ||
            !currentCharacterStats.pcStats.equals(str)
        ) {
            currentCharacterStats = new RealmCharacterStats();
            currentCharacterStats.decode(str);
        }

        if (charMap == null) {
            return;
        }
        RealmCharacter r = charMap.get(charId);
        if (
            r != null && r.charStats != null && !r.charStats.pcStats.equals(str)
        ) {
            r.updateCharStats(currentCharacterStats);
        }
    }

    /**
     * Gets the dungeion completion stats of the currently played character.
     * Note! only available if the instance have been changed at least ones from starting the app.
     *
     * @return Currently playing character stats.
     */
    public RealmCharacterStats getCurrentDungeonStats() {
        if (currentCharacterStats == null) return null;
        return currentCharacterStats;
    }

    /**
     * Sets the time of the server.
     *
     * @param serverRealTimeMS Server time in milliseconds.
     */
    public void setTime(long serverRealTimeMS) {
        time = serverRealTimeMS;
        timePc = System.currentTimeMillis();
        if (timePcFirst == -1) timePcFirst = timePc;
    }

    /**
     * Packet updating players position
     *
     * @param p Move packet data
     */
    public void updatePlayersPos(MovePacket p) {
        if (player != null && p.records != null && p.records.length > 0) {
            player.pos = p.records[p.records.length - 1].pos;
        }
    }

    /**
     * Main update packet.
     *
     * @param p Update packet
     */
    public void update(UpdatePacket p) {
        for (int i = 0; i < p.tiles.length; i++) {
            mapTiles[p.tiles[i].x][p.tiles[i].y] = p.tiles[i].type;
        }
        for (int i = 0; i < p.newObjects.length; i++) {
            ObjectData object = p.newObjects[i];
            entityUpdate(object);
        }
        for (int i = 0; i < p.drops.length; i++) {
            int dropId = p.drops[i];
            crystalTracker.remove(dropId);
            Entity e = entityList.get(dropId);
            dropList.put(dropId, e);

            // Clean up minion ownership mapping when minion despawns
            minionOwnerMap.remove(dropId);
            if (e != null) {
                //                e.entityDropped(timePc);
                if (isPlayerEntity(e.objectType)) {
                    for (Map.Entry<
                        Integer,
                        Entity
                    > dropCheck : entityHitList.entrySet()) {
                        int k = dropCheck.getKey();
                        if (!dropList.containsKey(k)) {
                            dropCheck.getValue().addPlayerDrop(dropId, timePc);
                        }
                    }
                }
            }

            if (entityHitList.containsKey(dropId)) {
                killedEntitys.add(e);
            }

            playerListUpdated.remove(dropId);
            ParsePanelGUI.removePlayer(dropId);
        }
    }

    /**
     * Adds an entity to the entity lists as well as updates objects.
     *
     * @param object Entity object to be added or updated
     */
    private void entityUpdate(ObjectData object) {
        int id = object.status.objectId;
        boolean localPlayer = worldPlayerId >= 0 && id == worldPlayerId;
        boolean newObject = !entityList.containsKey(id);
        Entity entity = entityList.computeIfAbsent(id, idd ->
            new Entity(this, idd, timePc)
        );
        int idType = object.objectType;
        entity.entityUpdate(idType, object.status, timePc);

        if (newObject) {
            moonLightFlameCounter(idType);
            SecurityAbilityUseCheck.decoy(entity);
            customSoundAlert(idType);
        }
        if (petyard) {
            addPet(object);
        }
        if (!petyard && isCrystal(idType)) {
            crystalTracker.add(id);
        } else if (!petyard && isLootBag(idType) && !lootBags.contains(id)) {
            lootBags.add(id);
            lootTickContainer[lootTickToggle].add(entity);
        } else if (localPlayer || isPlayerEntity(idType)) {
            entityHitList.remove(id);
            playerList.put(id, entity);
            playerListUpdated.put(id, entity);
            if (localPlayer) {
                player = entity;
                entity.setUser(charId);
                publishMyInfoPlayer(player);
            } else {
                entity.isPlayer();
            }
            ParsePanelGUI.addPlayer(id, entity);
            packets.packetcapture.logger.DiscoveryLog.INSTANCE.inspectPlayer(entity);
        }
    }

    /**
     * Plays sound if user wants alerts when this type of entity shows up.
     *
     * @param idType ID of sound alert entity
     */
    private void customSoundAlert(int idType) {
        ArrayList<String> idEntityPing = getEntityIdPings();
        if (idEntityPing != null) {
            for (String id : idEntityPing) {
                if (String.valueOf(idType).equals(id)) {
                    Sound.custom.play();
                    break;
                }
            }
        }
    }

    /**

     * Method to connect loot bag drops to mobs that drop them.
     * Attribution logic is centralized in LootAttributionManager.
     */
    private void lootTick() {
        lootTickToggle ^= 1;

        if (!lootTickContainer[lootTickToggle].isEmpty()) {
            try {
                // First pass: determine mob associations and collect results (do not send yet)
                lootAttribution.beginLootTick(map != null ? map.seed : -1);
                tomato.realmshark.SendLoot.beginLootTick(
                    map != null ? map.seed : -1
                );

                ArrayList<Entity> processedBags = new ArrayList<>();

                ArrayList<Entity> processedDroppers = new ArrayList<>();

                for (Entity bag : lootTickContainer[lootTickToggle]) {
                    Entity mob = lootAttribution.findAttributionForBag(
                        this,
                        bag,
                        killedEntitys,
                        map != null ? map.seed : -1,
                        timePc
                    );
                    processedBags.add(bag);
                    processedDroppers.add(mob);
                }

                // Apply any per-tick overrides (e.g., HM/TR variants) after all fabricated attributions are known
                lootAttribution.applyPerTickOverrides();

                // Second pass: update stats + GUI + SendLoot (SendLoot invoked inside LootGUI)

                for (int i = 0; i < processedBags.size(); i++) {
                    Entity bag = processedBags.get(i);

                    Entity mob = processedDroppers.get(i);

                    if (map != null) {
                        dungeonStatData.updateItems(map.name, mob, bag);
                    }

                    tomato.bridge.BridgeService.getInstance().receive(this, map, bag, player, timePc);
                    LootGUI.update(map, bag, mob, player, timePc);
                }

                // Finish tick (decrement windows and reset if necessary)
                lootAttribution.endLootTick();
            } catch (Exception e) {
                e.printStackTrace();
            }
            lootTickContainer[lootTickToggle].clear();
        }

        if (!killedEntitys.isEmpty()) {
            killedEntitys.clear();
        }
    }

    private void addPet(ObjectData object) {
        Entity value = entityList.get(object.status.objectId);
        if (value != null) publishYardPet(value, object.status.stats);
    }

    private void publishYardPet(Entity value, StatData[] delta) {
        boolean isPet = progression.hasPetObject(value.id);
        for (StatData field : delta) if (field.statTypeNum >= 81 && field.statTypeNum <= 95) { isPet = true; break; }
        if (!isPet) return;
        Map<Integer, FieldCapture> fields = new HashMap<>();
        for (StatData field : delta) fields.put(field.statTypeNum, value.fieldCapture(field.statTypeNum));
        progression.pet(progression.scope(), value.id, new Stat(delta), value.observedAt(), "Pet Yard capture", fields);
    }

    /**
     * Checks if id of shatters king boss crystal type.
     *
     * @param id Entity id.
     * @return True if id matches a crystal.
     */
    private boolean isCrystal(int id) {
        return id == 46721 || id == 46771 || id == 29501 || id == 33656;
    }

    /**
     * Checks if id is a loot drop bag
     *
     * @param id Entity id type.
     * @return True if id is loot bag
     */
    private boolean isLootBag(int id) {
        return LootBags.isLootDropBag(id);
    }

    /**
     * Determines the floor pattern in shatters king fight.
     *
     * @return Gives a mask id indicating the crystal colors in the king fight.
     */
    public int floorPlanCrystals() {
        int mask = 0;
        for (int i : crystalTracker) {
            if (i == 46721) {
                mask |= 1;
            } else if (i == 46771) {
                mask |= 2;
            } else if (i == 29501) {
                mask |= 4;
            } else if (i == 33656) {
                mask |= 8;
            }
        }
        return mask;
    }

    /**
     * Checks if any guarded phase entities exist for Forgotten King fight.
     *
     * @return True if any of the guarded phase entities (33656, 33557, 33572) exist
     */
    public boolean hasGuardedPhaseEntity() {
        for (Entity entity : entityList.values()) {
            int objectType = entity.objectType;
            if (
                objectType == 33656 ||
                objectType == 33557 ||
                objectType == 33572
            ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if objectType is a player entity.
     *
     * @param objectType ID of the object
     * @return True if ID matches a player entity.
     */
    private boolean isPlayerEntity(int objectType) {
        return CharacterClass.isPlayerCharacter(objectType);
    }

    /**
     * Entity updates and server time from new tick packet.
     *
     * @param p New tick packet.
     */
    public void updateNewTick(NewTickPacket p) {
        setTime(p.serverRealTimeMS);
        for (int i = 0; i < p.status.length; i++) {
            int id = p.status[i].objectId;
            Entity entity = entityList.computeIfAbsent(id, idd ->
                new Entity(this, idd, timePc)
            );
            entity.updateStats(p.status[i], timePc);
            if (petyard) publishYardPet(entity, p.status[i].stats);
            if (playerListUpdated.containsKey(id)) packets.packetcapture.logger.DiscoveryLog.INSTANCE.inspectPlayer(entity);
        }
        SecurityAbilityUseCheck.decreaseDecoyCounter();
        lootTick();
    }

    /**
     * Creates a new projectile from the outgoing packet.
     *
     * @param p Projectile info.
     */
    public void playerShoot(PlayerShootPacket p) {
        Projectile proj = new Projectile(
            rng,
            player,
            p.weaponId,
            p.projectileId
        );
        // Store in the fixed-size array for quick access (legacy)
        if (p.bulletId >= 0 && p.bulletId < projectiles.length) {
            projectiles[p.bulletId] = proj;
        }
        // Also store in a keyed map using the shooter (owner) + bulletId so lookups are unambiguous
        if (player != null) {
            long key = (((long) player.id) << 32) | (p.bulletId & 0xffffffffL);
            playerProjectiles.put(key, proj);

            // Track SlotType 18 ability usage for DamagePacket invulnerability bypass
            Entity.trackSlotType18AbilityUse(player, timePc);
        }
    }

    /**
     * Projectile info of other players.
     *
     * @param p Projectile info
     */
    public void serverPlayerShoot(ServerPlayerShootPacket p) {
        // Track SlotType 18 ability usage for DamagePacket invulnerability bypass
        Entity ownerEntity = playerList.get(p.ownerId);
        if (ownerEntity != null) {
            Entity.trackSlotType18AbilityUse(ownerEntity, timePc);
        }

        /*
         * MINION/SUMMON DAMAGE ATTRIBUTION:
         * When pets, minions, traps, or other summons shoot projectiles, the game sends
         * ServerPlayerShootPacket with:
         *   - ownerId = the minion/summon entity's objectId (e.g., objectType=5805 for traps)
         *   - summonerId = the player owner's objectId (e.g., objectType=801 for players)
         *
         * We track this relationship in minionOwnerMap so that when DamagePacket arrives
         * with the minion's objectId as the attacker, we can attribute the damage to the
         * player owner instead of showing "NO_NAME" in DPS logs.
         *
         * Example flow:
         * 1. ServerPlayerShootPacket: ownerId=218776 (minion), summonerId=202734 (player "BinaryGhost")
         *    → We store: minionOwnerMap[218776] = 202734
         * 2. DamagePacket: objectId=218776 (minion did damage)
         *    → We lookup: minionOwnerMap.get(218776) → 202734
         *    → Damage attributed to "BinaryGhost" instead of "NO_NAME"
         */
        if (p.summonerId != 0 && p.ownerId != 0) {
            minionOwnerMap.put(p.ownerId, p.summonerId);
        }

        if (p.bulletCount > 1) {
            Projectile projectile = new Projectile(
                p.damage,
                p.containerType,
                p.bulletType,
                p.summonerId
            );
            for (int j = p.bulletId; j < p.bulletId + p.bulletCount; j++) {
                int arrIndex = (j % 256) + 256;
                if (arrIndex >= 0 && arrIndex < projectiles.length) {
                    projectiles[arrIndex] = projectile;
                }
                // Map by ownerId + bullet index so we can reliably resolve this projectile later
                long key =
                    (((long) p.ownerId) << 32) | (arrIndex & 0xffffffffL);
                playerProjectiles.put(key, projectile);
            }
        } else if (p.bulletId > 255 && p.bulletId < 512) {
            Projectile projectile = new Projectile(
                p.damage,
                p.containerType,
                p.bulletType,
                p.summonerId
            );
            // Snapshot origin info for this server-created projectile (ability item + scaling stat)
            try {
                ownerEntity = playerList.get(p.ownerId);
                if (
                    ownerEntity != null &&
                    ownerEntity.stat != null &&
                    ownerEntity.stat.get(StatType.INVENTORY_1_STAT) != null
                ) {
                    try {
                        int abilityId = ownerEntity.stat.get(
                            StatType.INVENTORY_1_STAT
                        ).statValue;
                        projectile.setOriginAbilityItem(abilityId);
                    } catch (Exception ignored) {}
                    try {
                        AbilityScalingManager asm =
                            AbilityScalingManager.getInstance();
                        AbilityScalingManager.AbilityScalingData sd =
                            asm.getScalingData(p.containerType);
                        if (sd != null && sd.scalingStat != null) {
                            if (ownerEntity.stat.get(sd.scalingStat) != null) {
                                projectile.setOriginScalingStat(
                                    ownerEntity.stat.get(
                                        sd.scalingStat
                                    ).statValue
                                );
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            projectiles[p.bulletId] = projectile;
            long key = (((long) p.ownerId) << 32) | (p.bulletId & 0xffffffffL);
            playerProjectiles.put(key, projectile);
        } else {
            // Best-effort: still add to map for wrapped variants
            Projectile projectile = new Projectile(
                p.damage,
                p.containerType,
                p.bulletType,
                p.summonerId
            );
            int arrIndex = (p.bulletId % 256) + 256;
            if (arrIndex >= 0 && arrIndex < projectiles.length) {
                projectiles[arrIndex] = projectile;
            }
            // Snapshot origin info for wrapped/server variant projectile
            try {
                ownerEntity = playerList.get(p.ownerId);
                if (
                    ownerEntity != null &&
                    ownerEntity.stat != null &&
                    ownerEntity.stat.get(StatType.INVENTORY_1_STAT) != null
                ) {
                    try {
                        int abilityId = ownerEntity.stat.get(
                            StatType.INVENTORY_1_STAT
                        ).statValue;
                        projectile.setOriginAbilityItem(abilityId);
                    } catch (Exception ignored) {}
                    try {
                        AbilityScalingManager asm =
                            AbilityScalingManager.getInstance();
                        AbilityScalingManager.AbilityScalingData sd =
                            asm.getScalingData(p.containerType);
                        if (sd != null && sd.scalingStat != null) {
                            if (ownerEntity.stat.get(sd.scalingStat) != null) {
                                projectile.setOriginScalingStat(
                                    ownerEntity.stat.get(
                                        sd.scalingStat
                                    ).statValue
                                );
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            long key = (((long) p.ownerId) << 32) | (arrIndex & 0xffffffffL);
            playerProjectiles.put(key, projectile);
        }
    }

    /**
     * Handles entity's being hit by users projectiles.
     *
     * @param p Info about what entity was hit by what projectile.
     */
    public void enemtyHit(EnemyHitPacket p) {
        // Attempt a reliable map lookup first using shooter (owner) + bulletId.
        Projectile projectile = null;
        int shooterIdCandidate = p.shooterID;
        long key =
            (((long) shooterIdCandidate) << 32) | (p.bulletId & 0xffffffffL);
        projectile = playerProjectiles.get(key);

        // If not found, try a few fallbacks: wrapped server index and direct array index
        if (projectile == null) {
            int wrappedIndex = (p.bulletId % 256) + 256;
            if (wrappedIndex >= 0 && wrappedIndex < projectiles.length) {
                projectile = projectiles[wrappedIndex];
                if (projectile != null) {
                    /*System.out.println(
                        "[RealmShark capture] enemtyHit: resolved projectile via wrapped array index=" +
                            wrappedIndex +
                            " for bulletId=" +
                            p.bulletId +
                            " owner=" +
                            p.shooterID
                    );*/
                }
            }
        }

        if (projectile == null) {
            if (p.bulletId >= 0 && p.bulletId < projectiles.length) {
                projectile = projectiles[p.bulletId];
                if (projectile != null) {
                    /*System.out.println(
                        "[RealmShark capture] enemtyHit: resolved projectile via direct array index=" +
                            p.bulletId +
                            " owner=" +
                            p.shooterID
                    );*/
                }
            }
        }

        // If still not found, we log for debugging.
        if (projectile == null) {
            /*System.out.println(
                "[RealmShark capture] enemtyHit: projectile not resolved for bulletId=" +
                    p.bulletId +
                    " owner=" +
                    p.shooterID
            );*/
        }

        int id = p.targetId;
        Entity target = entityList.computeIfAbsent(id, idd ->
            new Entity(this, idd, timePc)
        );

        int shooterId = p.shooterID;
        if (projectile != null && projectile.getSummonerId() != 0) {
            shooterId = projectile.getSummonerId();
        }
        Entity attacker = playerList.get(shooterId);
        target.userProjectileHit(attacker, projectile, timePc);
        if (!target.isPlayerCharacter() && !entityHitList.containsKey(id)) {
            entityHitList.put(id, target);
            if (attacker != null && attacker.isUser() && map != null) {
                dungeonStatData.updateEntityDamage(map.name, target);
            }
        }
        target.updateDamageTaken(timePc);
    }

    /**
     * Info related to damage taken on entity's.
     *
     * @param p Info on entity taking damage, amount and by what player.
     */
    public void damage(DamagePacket p) {
        int id = p.targetId;
        Entity target = entityList.computeIfAbsent(id, idd ->
            new Entity(this, idd, timePc)
        );

        /*
         * ATTACKER RESOLUTION & MINION DAMAGE ATTRIBUTION:
         * DamagePacket.objectId contains the entity ID of whoever/whatever dealt the damage.
         * This could be:
         *   1. A player (found in playerList)
         *   2. A pet/minion/summon/trap (found in entityList, not playerList)
         *
         * For minions/summons, we check minionOwnerMap (populated by ServerPlayerShootPacket)
         * to find the player owner and attribute damage to them instead of showing "NO_NAME".
         *
         * This ensures all player-owned entities' damage appears under the player's name in DPS logs.
         */
        Entity attacker = playerList.get(p.objectId);

        // Fallback to entityList if not found in playerList (handles pets, minions, summons, etc.)
        if (attacker == null) {
            attacker = entityList.get(p.objectId);

            if (attacker != null) {
                // Check if this entity is a minion/summon with a known owner
                Integer ownerId = minionOwnerMap.get(p.objectId);
                if (ownerId != null) {
                    Entity owner = playerList.get(ownerId);
                    if (owner != null) {
                        // Replace attacker with the owner for damage attribution
                        attacker = owner;
                    } else {
                        // Owner not found in playerList, ignore this damage
                        attacker = null;
                    }
                } else {
                    // Minion/summon has no owner mapping, ignore this damage
                    attacker = null;
                }
            }
        }

        if (p.damageAmount > 0) {
            Projectile projectile = new Projectile(p.damageAmount);
            target.genericDamageHit(attacker, projectile, timePc);
            if (!target.isPlayerCharacter() && !entityHitList.containsKey(id)) {
                entityHitList.put(id, target);
                if (attacker != null && attacker.isUser() && map != null) {
                    dungeonStatData.updateEntityDamage(map.name, target);
                }
            }
        }

        target.updateDamageTaken(timePc);
    }

    void recordInspectDamage(Entity target, Damage hit) {
        if (hit.owner != null && playerList.get(hit.owner.id) == hit.owner && !playerList.containsKey(target.id))
            packets.packetcapture.logger.DiscoveryLog.INSTANCE.inspectDamage(hit.owner, hit.damage, hit.time);
    }

    /**
     * Packet indicating player taking damage by enemy.
     */
    public void userDamage(PlayerHitPacket p) {
        if (player != null) {
            Entity e = entityList.get(p.objectId);
            long id = p.objectId + ((long) p.bulletId << 24);
            Projectile p1 = enemyProjectiles.get(id);
            if (p1 != null) {
                player.userDamageTaken(e, timePc, p1);
            }
        }
    }

    /**
     * Packet for projectiles seen by user.
     *
     * @param p Enemy projectile packet
     */
    public void enemyProjectile(EnemyShootPacket p) {
        Entity e = entityList.get(p.ownerId);
        boolean ap = false;
        if (e != null) {
            int etype = e.objectType;
            int btype = p.bulletType;
            try {
                ap = IdToAsset.getIdProjectileArmorPierces(etype, btype);
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < p.numShots; i++) {
            long id = p.ownerId + ((long) (p.bulletId + i) << 24);
            enemyProjectiles.put(id, new Projectile(p.damage, ap));
        }
    }

    /**
     * Aoe damage packet
     *
     * @param p Aoe packet
     */
    public void aoeDamage(AoePacket p) {
        if (player != null) {
            if (player.distSqrd(p.pos) < (p.radius * p.radius)) {
                player.userDamageTaken(
                    null,
                    timePc,
                    new Projectile(p.damage, p.armorPiercing)
                );
            }
        }
    }

    /**
     * Ground tile damage on player
     *
     * @param p Ground tile packet update
     */
    public void groundDamage(GroundDamagePacket p) {
        if (player != null) {
            WorldPosData pos = p.position;
            int id = mapTiles[(int) pos.x][(int) pos.y];
            int dmg = IdToAsset.getTileDamage(id);
            player.userDamageTaken(null, timePc, new Projectile(dmg, true));
        }
    }

    /**
     * Dungeons that should not be logged.
     *
     * @param dungName Map data name of the instance.
     * @return Dungeon that should be logged.
     */
    private static boolean isLoggedDungeon(String dungName) {
        switch (dungName) {
            case "{s.vault}": // vault
            case "Daily Quest Room": // quest room
            case "Pet Yard": // pet yard
            case "{s.guildhall}": // guild hall
            case "{s.nexus}": // nexus
            case "Grand Bazaar": // bazaar
                return false;
            default:
                return true;
        }
    }

    /**
     * Clears all data as instance is changing.
     */
    public void clear() {
        SecurityAbilityUseCheck.reset();
        invalidateRosterRequest();
        metadataAwaitingCreate = true;
        resetMyInfo(null, -1, -1);
        worldPlayerId = -1;
        charId = -1;
        time = -1;
        if (map != null && isLoggedDungeon(map.displayName)) {
            dpsData.add(
                new DpsData(
                    map,
                    entityHitList,
                    deathNotifications,
                    dungeonTime(),
                    timePcFirst,
                    dpsPacketLog,
                    player
                )
            );
            DpsGUI.updateLabel();
        }
        if (map != null) {
            dungeonStatData.updateDungeon(map.name, dungeonTime());
        }
        dpsPacketLog = new ArrayList<>();
        timePc = -1;
        timePcFirst = -1;
        rng = null;
        player = null;
        entityList.clear();
        playerList.clear();
        crystalTracker.clear();
        playerListUpdated.clear();
        dropList.clear();

        lootBags.clear();

        enemyProjectiles.clear();

        minionOwnerMap.clear();

        deathNotifications = new ArrayList<>();

        entityHitList = new HashMap<>();

        for (int[] row : mapTiles) {
            Arrays.fill(row, 0);
        }
        for (Projectile p : projectiles) {
            if (p != null) p.clear();
        }
        petyard = false;
        moonlightFlames = 0;
    }

    public Entity[] getEntityHitList() {
        return entityHitList.values().stream().filter(e -> !e.isPlayerCharacter()).toArray(Entity[]::new);
    }

    public void exaltUpdate(ExaltationUpdatePacket p) {
        int[] exalts = RealmCharacter.exalts.get((int) p.objType);
        int[] update = new int[] {
            p.dexterityProgress,
            p.speedProgress,
            p.vitalityProgress,
            p.wisdomProgress,
            p.defenseProgress,
            p.attackProgress,
            p.manaProgress,
            p.healthProgress,
        };
        if (!Arrays.equals(exalts, update)) {
            TreeMap<Integer, int[]> next = new TreeMap<>(RealmCharacter.exalts);
            next.put((int) p.objType, update);
            RealmCharacter.exalts = next;
            characterJournal().exalts(journalAccount, RealmCharacter.exalts);
        }
    }

    public void vaultPacketUpdate(VaultContentPacket p) {
        if (player != null) {
            if (player.stat.get(StatType.SEASONAL).statValue == 1) {
                vaultDataRecievedSeasonal = true;
                seasonalVault.vaultPacketUpdate(p);
            } else {
                vaultDataRecievedRegular = true;
                regularVault.vaultPacketUpdate(p);
            }
        }
    }

    public void characterListUpdate(ArrayList<RealmCharacter> chars) {
        characterListUpdate(chars, Collections.emptyMap());
    }

    private void characterListUpdate(ArrayList<RealmCharacter> chars, Map<Integer, PetAvailability> petStates) {
        if (chars == null) return;
        // A delayed roster must not roll the active character back to older API stats.
        for (RealmCharacter character : chars) {
            if (player != null && character.charId == charId) player.overlayCapturedCharacter(character);
        }
        if (journalAccount == null) journalPendingRoster = chars;
        else characterJournal().mergeRoster(journalAccount, chars);
        characterDataRecieved = true;
        this.chars = chars;
        charMap = new HashMap<>();
        for (RealmCharacter r : chars) {
            charMap.put(r.charId, r);
        }
        seasonalVault.clearChar();
        regularVault.clearChar();
        for (RealmCharacter c : chars) {
            if (c.equipment == null) continue;
            if (!c.presence.containsKey("seasonal")) continue;
            if (c.seasonal) {
                seasonalVault.updateCharInventory(c);
            } else {
                regularVault.updateCharInventory(c);
            }
        }
        RealmCharacter currentChar = charMap.get(charId);
        publishMyInfoPlayer(player); // Resolve account changes before binding metadata to its owner.
        pet = null; petOwner = null; petIdentity = null; petAvailability = PetAvailability.UNKNOWN;
        if (!metadataAwaitingCreate && player != null && player.id == worldPlayerId
            && myInfoIdentity.account != null && currentChar != null) {
            petAvailability = petStates.getOrDefault(charId, PetAvailability.UNKNOWN);
            if (validPetAbilities(currentChar)) {
                makePet(currentChar);
                petAvailability = PetAvailability.PRESENT;
            }
            petOwner = player;
            petIdentity = myInfoIdentity;
        }
        publishMyInfoPlayer(player);
        if (currentChar != null && myInfoIdentity.account != null) {
            Entity details = petEntity(currentChar);
            if (!currentChar.presence.isEmpty() && hasPetFields(currentChar)) {
                Map<Integer, FieldCapture> fields = new HashMap<>();
                for (StatType type : StatType.values()) {
                    FieldCapture field = currentChar.presence.get("pet." + type.get());
                    if (field != null) fields.put(type.get(), field);
                }
                progression.pet(progression.scope(), -1, details.stat, currentChar.receivedAt, "Equipped character metadata", fields);
            }
            StatData id = details.stat.get(StatType.PET_INSTANCE_ID_STAT);
            progression.equipped(progression.scope(), id != null ? PetAvailability.PRESENT : petAvailability, id == null ? null : id.statValue);
        } else progression.equipped(progression.scope(), PetAvailability.UNKNOWN, null);
        SwingUtilities.invokeLater(() -> {
            FameTablePanel.updateRealmChars();
        });
    }

    private static boolean validPetAbilities(RealmCharacter character) {
        int[] abilities = character.petAbilitys;
        if (abilities == null || abilities.length != 9) return false;
        for (int i = 0; i < 9; i += 3) {
            if (!character.presence.containsKey("pet." + (90 + i / 3)) || !character.presence.containsKey("pet." + (93 + i / 3))
                || abilities[i + 1] < 0 || abilities[i + 1] > 100 || abilities[i + 2] <= 0) return false;
        }
        return true;
    }

    private void makePet(RealmCharacter currentChar) {
        pet = petEntity(currentChar);
    }

    private static boolean hasPetFields(RealmCharacter c) {
        for (String field : c.presence.keySet()) if (field.startsWith("pet.")) return true;
        return false;
    }

    private Entity petEntity(RealmCharacter c) {
        Entity result = new Entity(this, -1, time);
        int[] types = {StatType.SKIN_ID.get(), 83, 82, 84, 81, 85, 87, 90, 93, 88, 91, 94, 89, 92, 95};
        int[] values = {c.petSkin, c.petType, 0, c.petRarity, c.petInstanceId, c.petMaxAbilityPower, 0,0,0,0,0,0,0,0,0};
        if (c.petAbilitys != null) System.arraycopy(c.petAbilitys, 0, values, 6, Math.min(9, c.petAbilitys.length));
        for (int i = 0; i < types.length; i++) if (c.presence.containsKey("pet." + types[i])) {
            StatData stat = new StatData(); stat.statTypeNum = types[i]; stat.statValue = values[i];
            for (StatType type : StatType.values()) if (type.get() == types[i]) { stat.statType = type; result.stat.set(type, stat); break; }
            if (types[i] == 82) stat.stringStatValue = c.petName;
        }
        return result;
    }

    /**
     * Handles character data by sending char list request to rotmg servers while in the daily quest room.
     * This is done here given pet yard and daily quest instance is the only instances where the char list
     * request can be done without being rejected by rotmg servers.
     * <p>
     * token Current client token string used in http request packet.
     */
    public void charListHttpRequest() {
        requestMetadata(true);
    }

    /**
     * Stores the token for char list requests.
     *
     * @param token Current client token.
     */
    public void updateToken(String token) {
        // HELLO is a new connection even when its access token was reused.
        metadataConnection++;
        metadataAwaitingCreate = true;
        resetMyInfo(null, -1, -1);
        metadataWorker.invalidate(false);
        if (!Objects.equals(this.token, token)) {
            resetAccountMetadata();
        }
        this.token = token;

        updateExalts(token);
    }

    private void updateExalts(String token) {
        if (updatedExaltStats || token == null || token.isEmpty()) return;
        requestMetadata(false);
    }

    private void invalidateRosterRequest() {
        rosterRequest = ++metadataSequence;
        metadataWorker.invalidate(true);
        journalPendingRoster = null;
    }

    private void resetAccountMetadata() {
        metadataAccount++;
        metadataWorker.invalidate(false);
        resetMyInfo(null, charId, worldPlayerId);
        progression.clearPets();
        journalAccount = null; journalPendingRoster = null;
        updatedExaltStats = false; characterDataRecieved = false;
        chars = null; charMap = null;
        RealmCharacter.exalts = new TreeMap<>();
        regularVault.clearChar(); seasonalVault.clearChar();
        SwingUtilities.invokeLater(LootGUI::updateExaltStats);
    }

    private void requestMetadata(boolean roster) {
        if (token == null || token.isEmpty()) return;
        long sequence = ++metadataSequence;
        if (roster) rosterRequest = sequence; else exaltRequest = sequence;
        metadataWorker.submit(new MetadataRequest(roster, sequence, metadataConnection, metadataAccount,
            journalAccount, token, charId));
    }

    /** Drained after UPDATE/NEWTICK established the authenticated local player. No I/O here. */
    void applyMetadataResponses() {
        if (!progression.scope().accepting || progression.scope().account == null) return;
        if (metadataAwaitingCreate || player == null || journalAccount == null) return;
        for (MetadataResponse response : metadataWorker.poll()) {
            if (response == null) continue;
            MetadataRequest request = response.request;
            if (request.connection != metadataConnection || request.accountGeneration != metadataAccount
                || request.sequence != (request.roster ? rosterRequest : exaltRequest)
                || (request.account != null && !request.account.equals(journalAccount))
                || (response.account != null && !response.account.equals(journalAccount))
                || (request.roster && request.characterId != charId)) continue;
            if (request.roster) {
                ArrayList<RealmCharacter> characters = new ArrayList<>();
                Map<Integer, PetAvailability> petStates = new HashMap<>();
                for (CharacterMetadata character : response.characters) {
                    RealmCharacter copy = character.copy();
                    characters.add(copy); petStates.put(copy.charId, character.petAvailability);
                }
                characterListUpdate(characters, petStates);
            } else updatedExaltStats = true;
            if (!response.exalts.isEmpty()) {
                TreeMap<Integer, int[]> next = new TreeMap<>(RealmCharacter.exalts);
                response.exalts.forEach((clazz, counts) -> {
                    int[] values = new int[8], observed = next.get(clazz);
                    // Exalts are cumulative; late account/roster responses cannot undo a packet update.
                    for (int i = 0; i < 8; i++) values[i] = Math.max(counts.get(i), observed == null ? 0 : observed[i]);
                    next.put(clazz, values);
                });
                RealmCharacter.exalts = next;
                characterJournal().exalts(journalAccount, next);
                SwingUtilities.invokeLater(LootGUI::updateExaltStats);
            }
        }
    }

    private static final class MetadataRequest {
        final boolean roster;
        final long sequence, connection, accountGeneration;
        final String account, token;
        final int characterId;
        MetadataRequest(boolean roster, long sequence, long connection, long accountGeneration,
                        String account, String token, int characterId) {
            this.roster = roster; this.sequence = sequence; this.connection = connection;
            this.accountGeneration = accountGeneration; this.account = account;
            this.token = token; this.characterId = characterId;
        }
        int slot() { return roster ? 0 : 1; }
    }

    /** At most one HTTP operation, two pending requests and two completed responses. */
    private static final class MetadataWorker {
        private final MetadataClient client;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "account-metadata"); thread.setDaemon(true); return thread;
        });
        private final MetadataRequest[] pending = new MetadataRequest[2], latest = new MetadataRequest[2];
        private final MetadataResponse[] completed = new MetadataResponse[2];
        private boolean running;
        MetadataWorker(MetadataClient client) { this.client = client; }
        synchronized void submit(MetadataRequest request) {
            int slot = request.slot();
            pending[slot] = latest[slot] = request; completed[slot] = null;
            if (!running) { running = true; executor.execute(this::drain); }
        }
        synchronized void invalidate(boolean rosterOnly) {
            for (int i = 0; i < (rosterOnly ? 1 : 2); i++) {
                pending[i] = latest[i] = null; completed[i] = null;
            }
        }
        synchronized MetadataResponse[] poll() {
            MetadataResponse[] result = completed.clone();
            Arrays.fill(completed, null);
            return result;
        }
        private void drain() {
            while (true) {
                MetadataRequest request;
                synchronized (this) {
                    if (pending[0] == null && pending[1] == null) {
                        running = false; notifyAll(); return;
                    }
                    int slot = pending[0] == null ? 1 : pending[1] == null ? 0
                        : pending[0].sequence < pending[1].sequence ? 0 : 1;
                    request = pending[slot]; pending[slot] = null;
                }
                try {
                    String xml = client.request(request.token, request.roster ? "char/list" : "account/listPowerUpStats");
                    synchronized (this) { if (latest[request.slot()] != request) continue; }
                    MetadataResponse response = parseMetadata(request, xml);
                    synchronized (this) {
                        if (latest[request.slot()] == request) completed[request.slot()] = response;
                    }
                } catch (Exception e) {
                    // Never log tokens, response bodies, or URL-containing exception messages.
                    System.err.println("Optional account metadata unavailable: " + e.getClass().getSimpleName());
                }
            }
        }
        synchronized boolean awaitIdle(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (running) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }
    }

    boolean awaitMetadataIdle(long millis) throws InterruptedException { return metadataWorker.awaitIdle(millis); }

    private static final class MetadataResponse {
        final MetadataRequest request;
        final String account;
        final List<CharacterMetadata> characters;
        final Map<Integer, List<Integer>> exalts;
        MetadataResponse(MetadataRequest request, String account, List<CharacterMetadata> characters,
                         Map<Integer, List<Integer>> exalts) {
            this.request = request; this.account = account;
            this.characters = Collections.unmodifiableList(new ArrayList<>(characters));
            this.exalts = Collections.unmodifiableMap(new TreeMap<>(exalts));
        }
    }

    /** No mutable legacy bean or array is exposed from a response to the live model. */
    private static final class CharacterMetadata {
        private final RealmCharacter value;
        private final PetAvailability petAvailability;
        CharacterMetadata(RealmCharacter value, PetAvailability petAvailability) {
            this.value = copyCharacter(value); this.petAvailability = petAvailability;
        }
        RealmCharacter copy() { return copyCharacter(value); }
    }

    private static RealmCharacter copyCharacter(RealmCharacter s) {
        RealmCharacter c = new RealmCharacter();
        c.presence.putAll(s.presence); c.receivedAt = s.receivedAt; c.rosterRevision = s.rosterRevision;
        c.charId=s.charId; c.classNum=s.classNum; c.classString=s.classString; c.level=s.level; c.skin=s.skin;
        c.exp=s.exp; c.fame=s.fame; c.seasonal=s.seasonal; c.backpack=s.backpack; c.qs3=s.qs3;
        c.equipment=s.equipment==null?null:s.equipment.clone(); c.equipQS=s.equipQS==null?null:s.equipQS.clone(); c.date=s.date;
        c.capturedStatMask=s.capturedStatMask; c.hp=s.hp; c.mp=s.mp; c.atk=s.atk; c.def=s.def;
        c.spd=s.spd; c.dex=s.dex; c.vit=s.vit; c.wis=s.wis; c.pcStats=s.pcStats;
        if (s.charStats != null) {
            // RealmCharacterStats is a large flat legacy value bean (ints, String, int[]).
            c.charStats = new RealmCharacterStats();
            try {
                for (java.lang.reflect.Field field : RealmCharacterStats.class.getFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    Object value = field.get(s.charStats);
                    field.set(c.charStats, value instanceof int[] ? ((int[]) value).clone() : value);
                }
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        c.petName=s.petName; c.petCreatedOn=s.petCreatedOn; c.petSkin=s.petSkin; c.petType=s.petType;
        c.petInstanceId=s.petInstanceId; c.petMaxAbilityPower=s.petMaxAbilityPower; c.petRarity=s.petRarity;
        c.petAbilitys=s.petAbilitys==null?null:s.petAbilitys.clone();
        return c;
    }

    /** Unlike the legacy RealmCharacter parsers, this decoder never writes global exalts. */
    private static MetadataResponse parseMetadata(MetadataRequest request, String xml) throws Exception {
        if (xml == null || xml.trim().isEmpty()) throw new IOException("Missing metadata");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        if (!(request.roster ? "Chars" : "AccountPowerups").equals(root.getTagName()))
            throw new IOException("Rejected metadata response");
        Element accountNode = child(root, "Account");
        String accountId = text(accountNode == null ? root : accountNode, "AccountId");
        if (accountId == null && accountNode != null && accountNode.hasAttribute("id")) accountId = accountNode.getAttribute("id");
        String account = accountId == null || accountId.isEmpty() ? null : CharacterJournal.accountKey(accountId);
        List<CharacterMetadata> characters = new ArrayList<>();
        Map<Integer, List<Integer>> exalts = new TreeMap<>();
        if (request.roster) {
            for (Element node : children(root)) if ("Char".equals(node.getTagName())) {
                RealmCharacter character = parseCharacter(node);
                Element pet = child(node, "Pet");
                // An explicitly empty Pet element is absence. Omitted or incomplete metadata is unknown.
                PetAvailability state = pet != null && !pet.hasAttributes() && children(pet).isEmpty()
                    && pet.getTextContent().trim().isEmpty() ? PetAvailability.ABSENT : PetAvailability.UNKNOWN;
                characters.add(new CharacterMetadata(character, state));
            }
            org.w3c.dom.NodeList stats = root.getElementsByTagName("ClassStats");
            for (int i = 0; i < stats.getLength(); i++) {
                Element stat = (Element) stats.item(i);
                if (stat.getParentNode() instanceof Element && "PowerUpStats".equals(((Element) stat.getParentNode()).getTagName()))
                    exalts.put(Integer.parseInt(stat.getAttribute("class")), exaltCounts(stat.getTextContent().trim().split(",")));
            }
        } else {
            String[] names = {"Dex", "Spd", "Vit", "Wis", "Def", "Att", "Mana", "Life"};
            for (Element row : children(root)) if ("ClassPowerup".equals(row.getTagName())) {
                String[] values = new String[8];
                for (int i = 0; i < 8; i++) { String value = text(row, names[i]); values[i] = value == null ? "0" : value; }
                exalts.put(Integer.parseInt(text(row, "Class")), exaltCounts(values));
            }
        }
        return new MetadataResponse(request, account, characters, exalts);
    }

    private static List<Integer> exaltCounts(String[] values) throws IOException {
        if (values.length != 8) throw new IOException("Invalid exalt counts");
        List<Integer> result = new ArrayList<>();
        for (String value : values) {
            int count = Integer.parseInt(value.trim());
            if (count < 0) throw new IOException("Invalid exalt count");
            result.add(count);
        }
        return Collections.unmodifiableList(result);
    }

    private static RealmCharacter parseCharacter(Element node) {
        RealmCharacter c = new RealmCharacter();
        c.receivedAt = System.currentTimeMillis();
        c.charId = Integer.parseInt(node.getAttribute("id"));
        String[] statNames = {"MaxHitPoints", "MaxMagicPoints", "Attack", "Defense", "Speed", "Dexterity", "HpRegen", "MpRegen"};
        int[] stats = new int[8];
        for (int i = 0; i < 8; i++) {
            String value = text(node, statNames[i]);
            if (value != null) { stats[i] = Integer.parseInt(value); c.capturedStatMask |= 1 << i; c.supplied("stat." + i, c.receivedAt, "Character list"); }
        }
        c.hp=stats[0]; c.mp=stats[1]; c.atk=stats[2]; c.def=stats[3]; c.spd=stats[4]; c.dex=stats[5]; c.vit=stats[6]; c.wis=stats[7];
        for (Element field : children(node)) {
            String value = field.getTextContent().trim();
            switch (field.getTagName()) {
                case "ObjectType": c.classNum=Short.parseShort(value); c.setClassString(); c.supplied("class"); break;
                case "Equipment":
                    c.equipment=Arrays.stream(value.split(",")).mapToInt(s -> Integer.parseInt(s.split("#")[0])).toArray();
                    for (int i = 0; i < Math.min(28, c.equipment.length); i++) c.supplied("equipment." + i);
                    break;
                case "EquipQS": c.equipQS=value.split(","); break;
                case "Level": c.level=Integer.parseInt(value); c.supplied("level"); break;
                case "Texture": c.skin=Integer.parseInt(value); c.supplied("skin"); break;
                case "CreationDate": c.date=value; c.supplied("created"); break;
                case "HasBackpack": c.backpack="1".equals(value); break;
                case "Has3Quickslots": c.qs3="1".equals(value); break;
                case "Seasonal":
                    if ("True".equalsIgnoreCase(value) || "False".equalsIgnoreCase(value)) { c.seasonal=Boolean.parseBoolean(value); c.supplied("seasonal"); }
                    break;
                case "Exp": c.exp=Long.parseLong(value); break;
                case "CurrentFame": c.fame=Long.parseLong(value); c.supplied("fame"); break;
                case "PCStats":
                    c.pcStats=value;
                    try { c.charStats=new RealmCharacterStats(); c.charStats.decode(value); }
                    catch (RuntimeException e) { c.charStats=null; }
                    break;
                case "Pet":
                    c.petCreatedOn=field.getAttribute("createdOn"); c.petName=field.getAttribute("name");
                    c.petInstanceId=attributeInt(field,"instanceId"); c.petMaxAbilityPower=attributeInt(field,"maxAbilityPower");
                    c.petRarity=attributeInt(field,"rarity"); c.petSkin=attributeInt(field,"skin"); c.petType=attributeInt(field,"type");
                    String[] attributes = {"name", "instanceId", "maxAbilityPower", "rarity", "skin", "type"};
                    int[] types = {82, 81, 85, 84, StatType.SKIN_ID.get(), 83};
                    for (int i = 0; i < attributes.length; i++) if (field.hasAttribute(attributes[i])) c.supplied("pet." + types[i]);
                    Element abilities=child(field,"Abilities");
                    if (abilities != null) {
                        int[] values = new int[9];
                        int index = 0;
                        for (Element ability : children(abilities)) {
                            if (!"Ability".equals(ability.getTagName()) || index >= 3) continue;
                            String[] names = {"points", "power", "type"};
                            for (int j = 0; j < 3; j++) if (ability.hasAttribute(names[j])) {
                                values[index * 3 + j] = attributeInt(ability, names[j]); c.supplied("pet." + (87 + j * 3 + index));
                            }
                            index++;
                        }
                        c.petAbilitys=values;
                    }
                    break;
                default: break;
            }
        }
        return c;
    }

    private static int attributeInt(Element node, String name) {
        return node.hasAttribute(name) ? Integer.parseInt(node.getAttribute(name)) : 0;
    }
    private static List<Element> children(Element node) {
        List<Element> result = new ArrayList<>();
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) result.add((Element)c);
        return result;
    }
    private static Element child(Element node, String name) {
        for (Element c : children(node)) if (name.equals(c.getTagName())) return c;
        return null;
    }
    private static String text(Element node, String name) {
        Element c = child(node, name);
        return c == null ? null : c.getTextContent().trim();
    }

    /**
     * Incoming text data.
     *
     * @param p Text info.
     */

    public void text(TextPacket p) {
        if (
            p.text.equals(
                "I SAID DO NOT INTERRUPT ME! For this I shall hasten your end!"
            )
        ) {
            Entity e = entityList.get(p.objectId);

            if (e != null) {
                e.dammahCountered = true;
            }
        }

        // Centralized loot attribution trigger handling
        lootAttribution.handleTextPacket(p, map != null ? map.seed : -1);

        ChatGUI.updateChat(p);
    }

    /**
     * Handles notification packets.
     *
     * @param packet Notification packet
     */
    public void notification(NotificationPacket packet) {
        if (packet.effect == NotificationEffectType.PlayerDeath) {
            deathNotifications.add(packet);
        }
        KeypopGUI.packet(this, packet);
    }

    public ArrayList<NotificationPacket> getDeathNotifications() {
        return deathNotifications;
    }

    public long dungeonTime() {
        return timePc - timePcFirst;
    }

    public void logPacket(Packet packet) {
        dpsPacketLog.add(packet);
    }

    public void bootload() {
        dungeonStatData.load();
    }

    /** Bind the initialized history after the view exists, without reading the file again. */
    public void publishDungeonStats() {
        tomato.gui.stats.DungeonStats.update(dungeonStatData, null);
    }

    /**
     * Counts the number of moonlight boss flames that are observed by the player in moonlight village.
     *
     * @param idType Object ID to check if it's a boss flame.
     */
    private void moonLightFlameCounter(int idType) {
        if (idType == MOONLIGHT_BOSS_FLAME_ID) moonlightFlames++;
    }

    /**
     * Gets the current character ID.
     *
     * @return Current character ID, or -1 if no character is loaded.
     */
    public int getCharId() {
        return charId;
    }

    /**
     * Gets the number of flames from moonlight village boss phases
     *
     * @return Moonlight village boss flames
     */
    public int getMoonlightFlameCount() {
        return moonlightFlames;
    }

    /**
     * Used to reset the flames in moonlight village to separate umi flames from other boss flames.
     */
    public void resetMoonlightFlames() {
        moonlightFlames = 0;
    }

    /** Get and set Chat Messages the player wants to ping when received.
     *
     * @param a Array of all the messages
     */
    public void setChatMessagePings(ArrayList<String> a) {
        savePropList(a, "chatPingMessages");
    }

    public ArrayList<String> getChatMessagePings() {
        ArrayList<String> result = propLists.get("chatPingMessages");
        return result != null ? result : new ArrayList<>();
    }

    /** Get and set Entity ID's the player wants to ping when appearing.
     *
     * @param a Array of all the entity IDs
     */
    public void setIdEntityPing(ArrayList<String> a) {
        savePropList(a, "entityIdPings");
    }

    public ArrayList<String> getEntityIdPings() {
        ArrayList<String> result = propLists.get("entityIdPings");
        return result != null ? result : new ArrayList<>();
    }

    /** Get and set Items the player wants to ping when appearing.
     *
     * @param a Array of all the item values
     */
    public void setItemPing(ArrayList<String> a) {
        savePropList(a, "itemPings");
    }

    public ArrayList<String> getItemPings() {
        ArrayList<String> result = propLists.get("itemPings");
        return result != null ? result : new ArrayList<>();
    }

    public boolean isItemPing(String item) {
        ArrayList<String> itemPing = propLists.get("itemPings");
        if (itemPing != null) {
            for (String s : itemPing) {
                if (item.toLowerCase().contains(s.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if any enchant in the enchant text matches selected enchant pings
     */
    public boolean isEnchantPing(String enchantText) {
        if (enchantText == null || enchantText.isEmpty()) {
            return false;
        }

        // Load saved enchant ping selections
        String saved = PropertiesManager.getProperty("enchantPing.selected");
        if (saved == null || saved.trim().isEmpty()) {
            return false;
        }

        Set<Short> selectedEnchants = new HashSet<>();
        String[] parts = saved.split(",");
        for (String p : parts) {
            try {
                short v = Short.parseShort(p.trim());
                selectedEnchants.add(v);
            } catch (NumberFormatException ignored) {}
        }

        // Check each enchant line against selected enchants
        // Format is "EnchantName(ID)" per line
        String[] enchantLines = enchantText.split("\n");
        for (String enchantLine : enchantLines) {
            // Parse enchant ID from the line (format: "EnchantName(ID)")
            if (enchantLine.contains("(") && enchantLine.contains(")")) {
                try {
                    int start = enchantLine.lastIndexOf("(") + 1;
                    int end = enchantLine.lastIndexOf(")");
                    String idStr = enchantLine.substring(start, end);
                    short enchantId = Short.parseShort(idStr);
                    if (selectedEnchants.contains(enchantId)) {
                        return true;
                    }
                } catch (
                    NumberFormatException
                    | IndexOutOfBoundsException ignored
                ) {}
            }
        }

        return false;
    }

    /**
     * Get a list property from the data storage.
     *
     * @param propName  Property name to get
     * @param list      Property list to set
     */
    public void setPropList(String propName, ArrayList<String> list) {
        propLists.put(propName, list);
    }

    /**
     * Load a list property from the properties manager into the data storage.
     *
     * @param propName  Property name to load
     * @param delimiter Delimiter used to split the property string
     */
    public void loadPropList(String propName, String delimiter) {
        ArrayList<String> arr = new ArrayList<>();
        String messages = PropertiesManager.getProperty(propName);
        if (messages == null) return;
        for (String s : messages.split(delimiter)) {
            if (!s.isEmpty()) {
                arr.add(s);
            }
        }
        this.setPropList(propName, arr);
    }

    public void loadPropList(String propName) {
        loadPropList(propName, "§");
    }

    /**
     * Save a list property from the data storage into the properties manager.
     *
     * @param list      List to save
     * @param propName  Property name to save
     * @param delimiter Delimiter used to join the list into a string
     */
    public void savePropList(
        ArrayList<String> list,
        String propName,
        String delimiter
    ) {
        this.setPropList(propName, list);
        if (list == null || list.isEmpty()) {
            PropertiesManager.setProperties(propName, "");
            return;
        }
        StringBuilder s = new StringBuilder();
        for (String i : list) {
            s.append(delimiter).append(i);
        }
        PropertiesManager.setProperties(propName, s.substring(1));
    }

    public void savePropList(ArrayList<String> list, String propName) {
        savePropList(list, propName, "§");
    }

    // --- Moonlight Village Umi / Miko loot & other attribution support ---
    // Umi (20493) and Miko (20451) wander off without death packets.
    // After their concluding dialogue lines we attribute ALL loot bags that appear
    // on the very next lootTick (only that tick) to the corresponding mob id.
    // Implementation: text() sets nextTickAttributionMobId, lootTick() consumes it once.

    // Centralized loot attribution manager instance
    private final LootAttributionManager lootAttribution =
        new LootAttributionManager();

    // Centralized manager for next-tick loot attribution logic
    private static class LootAttributionManager {

        // --- Known special mob IDs ---
        private static final int UMI_KITSUNE_ID = 20493;
        private static final int MIKO_DANCER_ID = 20451;
        private static final int VOID_ENTITY_ID = 45076;
        private static final int BRIDGE_SENTINEL_ID = 29003;
        private static final int TWILIGHT_ARCHMAGE_ID = 29021;
        private static final int ACCURSED_KING_ID = 29039;

        // --- State for attribution windows ---
        private int nextTickAttributionMobId = -1; // -1 = no attribution pending

        private String forcedVariantSuffix = null; // if non-null, force variant suffix (e.g., HM/TR) during the attribution window

        private int nextTickAttributionSeed = -1; // map.seed at time of trigger to prevent cross-instance carryover

        private int remainingAttributionTicks = 0; // number of loot ticks still allowed to attribute (e.g. 2 for Goddess of Revelry)

        // --- Per-tick bookkeeping ---
        private int currentTickSeed = -1;
        private final ArrayList<Entity> fabricatedAttributions =
            new ArrayList<>();

        // Handle TextPacket triggers that open attribution windows

        void handleTextPacket(TextPacket p, int seed) {
            // Kitsune Umi (Moonlight Village)

            if (
                "#Kitsune Umi".equals(p.name) &&
                "This fully concludes the Moonlight Festival!".equals(p.text)
            ) {
                openWindow(UMI_KITSUNE_ID, seed, 2, null);
                return;
            }

            // Dancer Miko (Moonlight Village)

            if (
                "#Dancer Miko".equals(p.name) &&
                "Thank you all for coming tonight.".equals(p.text)
            ) {
                openWindow(MIKO_DANCER_ID, seed, 2, null);
                return;
            }

            // Umi, Goddess of Revelry (Moonlight Village - True Umi variant, allow delayed bag)

            if (
                "#Umi, Goddess of Revelry".equals(p.name) &&
                "This fully concludes the Moonlight Festival.".equals(p.text)
            ) {
                openWindow(UMI_KITSUNE_ID, seed, 2, "TR"); // initial + delayed bag
                return;
            }

            // Void Entity (The Void, allow delayed bag)

            if (
                "#Void Entity".equals(p.name) &&
                "You fools... You can never truly defeat me! I am in all of you! I AM all of you!".equals(
                    p.text
                )
            ) {
                openWindow(VOID_ENTITY_ID, seed, 2, null);
                return;
            }

            // Bridge Sentinel (The Shatters)

            if (
                "#The Bridge Sentinel".equals(p.name) &&
                "I tried to protect you... I have failed.".equals(p.text)
            ) {
                openWindow(BRIDGE_SENTINEL_ID, seed, 2, null); // initial + delayed bag
                return;
            }

            if (
                "#Valen the Unbreakable".equals(p.name) &&
                "I see now... my strength could not have held against this growing power.".equals(
                    p.text
                )
            ) {
                openWindow(BRIDGE_SENTINEL_ID, seed, 2, "HM"); // initial + delayed bag
                return;
            }

            // Twilight Archmage (The Shatters)

            if (
                "#Twilight Archmage".equals(p.name) &&
                "Wait, there's still time! I JUST NEED MORE POWER! WAIT!".equals(
                    p.text
                )
            ) {
                openWindow(TWILIGHT_ARCHMAGE_ID, seed, 2, null); // initial + delayed bag
                return;
            }

            if (
                "#Nox the Wild Shadow".equals(p.name) &&
                "Unworthy as you are to know what hides beyond, I've had... an epiphany. So in case you've failed to realize...".equals(
                    p.text
                )
            ) {
                openWindow(TWILIGHT_ARCHMAGE_ID, seed, 2, "HM"); // initial + delayed bag
                return;
            }

            // King Azamoth (The Shatters)

            if (
                "#The Accursed King".equals(p.name) &&
                "...do you truly think your end will be any different?".equals(
                    p.text
                )
            ) {
                openWindow(ACCURSED_KING_ID, seed, 2, null); // initial + delayed bag
                return;
            }

            if (
                "#King Azamoth".equals(p.name) &&
                "This fate is mine to bear... not hers.".equals(p.text)
            ) {
                openWindow(ACCURSED_KING_ID, seed, 2, "HM"); // initial + delayed bag
                return;
            }
        }

        // Open an attribution window (generic)

        private void openWindow(
            int mobId,
            int seed,
            int ticks,
            String forcedVariantSuffix
        ) {
            nextTickAttributionMobId = mobId;

            nextTickAttributionSeed = seed;

            this.forcedVariantSuffix = forcedVariantSuffix;
            remainingAttributionTicks = ticks;
        }

        // Begin a loot tick (reset per-tick structures)

        void beginLootTick(int currentSeed) {
            this.currentTickSeed = currentSeed;
            fabricatedAttributions.clear();
        }

        // Determine attribution for a single bag
        Entity findAttributionForBag(
            TomatoData parent,
            Entity bag,
            ArrayList<Entity> killedEntitys,
            int mapSeed,
            long timePc
        ) {
            Entity mob = null;
            double best = Double.MAX_VALUE;

            // Prefer killed this tick (closest)
            for (Entity k : killedEntitys) {
                double d = bag.distSqrd(k.pos);
                if (d < best) {
                    best = d;
                    mob = k;
                }
            }

            // Fallback to next-tick attribution window, guarded by map seed
            if (
                mob == null &&
                remainingAttributionTicks > 0 &&
                mapSeed == nextTickAttributionSeed &&
                nextTickAttributionMobId > 0
            ) {
                Entity attribution = new Entity(
                    parent,
                    nextTickAttributionMobId,
                    timePc
                ); // ephemeral fabricated entity
                attribution.objectType = nextTickAttributionMobId;
                mob = attribution;
                fabricatedAttributions.add(attribution);
            }

            return mob;
        }

        // Apply HM/TR overrides after we know how many fabricated attributions occurred this tick
        void applyPerTickOverrides() {
            if (fabricatedAttributions.isEmpty()) return;

            if (forcedVariantSuffix != null && !forcedVariantSuffix.isEmpty()) {
                for (Entity f : fabricatedAttributions) {
                    f.lootMobIdOverride =
                        String.valueOf(f.objectType) + forcedVariantSuffix;
                }
            } else if (fabricatedAttributions.size() > 1) {
                for (Entity f : fabricatedAttributions) {
                    if (f.objectType == UMI_KITSUNE_ID) {
                        f.lootMobIdOverride = "20493HM";
                    } else if (f.objectType == MIKO_DANCER_ID) {
                        f.lootMobIdOverride = "20451HM";
                    }
                }
            }
        }

        // End-of-tick housekeeping (decrement and possibly reset attribution window)
        void endLootTick() {
            if (remainingAttributionTicks > 0) {
                remainingAttributionTicks--;
            }

            if (remainingAttributionTicks == 0) {
                nextTickAttributionMobId = -1;

                forcedVariantSuffix = null;
                nextTickAttributionSeed = -1;
            }
        }
    }
}
