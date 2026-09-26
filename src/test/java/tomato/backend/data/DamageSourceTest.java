package tomato.backend.data;

import org.junit.Test;
import packets.incoming.AllyShootPacket;
import packets.incoming.DamagePacket;
import packets.incoming.ServerPlayerShootPacket;

import java.io.*;

import static org.junit.Assert.*;

public class DamageSourceTest {
    // Object types absent from any asset list, so classification uses the caller's fallback.
    private static final int UNLISTED_ITEM = 0x7ff0_0001, SUMMON_TYPE = 0x7ff0_0002;

    @Test public void slotTypesMapToWeaponAbilityAndItemEffects() {
        for (int slot : new int[]{1, 2, 3, 8, 17, 24}) assertEquals(DamageSource.WEAPON, DamageSource.forSlot(slot, DamageSource.UNKNOWN));
        for (int slot : new int[]{6, 7, 9, 14}) assertEquals(DamageSource.ITEM_EFFECT, DamageSource.forSlot(slot, DamageSource.UNKNOWN));
        for (int slot : new int[]{4, 11, 15, 20, 21, 25}) assertEquals(DamageSource.ABILITY, DamageSource.forSlot(slot, DamageSource.UNKNOWN));
        assertEquals(DamageSource.OTHER, DamageSource.forSlot(0, DamageSource.OTHER));
        assertEquals(DamageSource.WEAPON, DamageSource.forItem(UNLISTED_ITEM, DamageSource.WEAPON));
        assertEquals(DamageSource.UNKNOWN, DamageSource.forItem(-1, DamageSource.UNKNOWN));
    }

    @Test public void allyShotLabelsTheMatchingServerDamageReport() {
        TomatoData data = fixture();
        AllyShootPacket shot = new AllyShootPacket(); shot.ownerId = 2; shot.bulletId = 40; shot.containerType = UNLISTED_ITEM;
        data.allyShoot(shot);
        data.timePc = 1500;
        Damage hit = damage(data, 2, 40, 120);
        assertEquals(DamageSource.WEAPON, DamageSource.of(hit));
        assertEquals(UNLISTED_ITEM, DamageSource.itemOf(hit));
        assertEquals("Weapon · Item #" + UNLISTED_ITEM, DamageSource.describe(hit));
    }

    @Test public void unmatchedAndExpiredShotsAreOtherServerDamage() {
        TomatoData data = fixture();
        assertEquals(DamageSource.OTHER, DamageSource.of(damage(data, 2, 7, 50)));
        AllyShootPacket shot = new AllyShootPacket(); shot.ownerId = 2; shot.bulletId = 8; shot.containerType = UNLISTED_ITEM;
        data.allyShoot(shot);
        data.timePc += TomatoData.SHOT_SOURCE_WINDOW_MS + 1;
        Damage late = damage(data, 2, 8, 50);
        assertEquals("A reused or stale bullet ID must not borrow an old weapon", DamageSource.OTHER, DamageSource.of(late));
        assertNull(DamageSource.itemName(late));
    }

    @Test public void summonDamageIsAttributedToOwnerAndLabelledWithTheSummon() {
        TomatoData data = fixture();
        Entity minion = new Entity(data, 30, 0); minion.objectType = SUMMON_TYPE; data.entityList.put(30, minion);
        ServerPlayerShootPacket shot = new ServerPlayerShootPacket();
        shot.ownerId = 30; shot.summonerId = 2; shot.bulletId = 300; shot.bulletCount = 1; shot.containerType = UNLISTED_ITEM; shot.damage = 90;
        data.serverPlayerShoot(shot);
        Damage hit = damage(data, 30, 300, 90);
        assertEquals(2, hit.owner.id);
        assertEquals(DamageSource.SUMMON, DamageSource.of(hit));
        assertEquals(SUMMON_TYPE, DamageSource.itemOf(hit));
    }

    @Test public void playersOwnServerShotsUseTheFiringItemAcrossEveryBullet() {
        TomatoData data = fixture();
        ServerPlayerShootPacket shot = new ServerPlayerShootPacket();
        shot.ownerId = 2; shot.summonerId = 2; shot.bulletId = 260; shot.bulletCount = 3; shot.containerType = UNLISTED_ITEM; shot.damage = 70;
        data.serverPlayerShoot(shot);
        for (int bullet = 260; bullet < 263; bullet++) {
            Damage hit = damage(data, 2, bullet, 70);
            assertEquals("Unlisted server-created shots are item effects", DamageSource.ITEM_EFFECT, DamageSource.of(hit));
            assertEquals(UNLISTED_ITEM, DamageSource.itemOf(hit));
        }
        assertEquals(DamageSource.OTHER, DamageSource.of(damage(data, 2, 263, 70)));
    }

    @Test public void legacyProjectilesReadAsUnknownAndKeepTheirSerializedForm() throws Exception {
        assertEquals(5852326746681358741L, ObjectStreamClass.lookup(Projectile.class).getSerialVersionUID());
        Damage legacy = new Damage(null, new Projectile((short) 10, UNLISTED_ITEM, 0, 2), 0, 10);
        assertEquals(DamageSource.UNKNOWN, DamageSource.of(legacy));
        assertEquals("Unknown source · Item #" + UNLISTED_ITEM, DamageSource.describe(legacy));
        assertEquals("Unknown source", DamageSource.describe(new Damage(null, 0, 5)));

        Projectile tagged = new Projectile(10); tagged.setSource(DamageSource.ABILITY, UNLISTED_ITEM);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(tagged); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Projectile read = (Projectile) in.readObject();
            assertEquals(DamageSource.ABILITY, read.getSource()); assertEquals(UNLISTED_ITEM, read.getSourceItem());
        }
        assertEquals(DamageSource.ABILITY, new Projectile(tagged).getSource());
    }

    private static TomatoData fixture() {
        TomatoData data = new TomatoData();
        data.timePc = 1000;
        Entity player = new Entity(data, 2, 0); player.objectType = 768;
        data.playerList.put(2, player); data.entityList.put(2, player);
        Entity enemy = new Entity(data, 99, 0); data.entityList.put(99, enemy);
        return data;
    }

    private static Damage damage(TomatoData data, int attacker, int bullet, int amount) {
        DamagePacket packet = new DamagePacket();
        packet.targetId = 99; packet.objectId = attacker; packet.bulletId = bullet; packet.damageAmount = amount;
        data.damage(packet);
        java.util.ArrayList<Damage> hits = data.entityList.get(99).getDamageList();
        return hits.get(hits.size() - 1);
    }
}
