package tomato.gui.myinfo;

import java.util.HashMap;

/** Installs synthetic weapon definitions for estimate tests and restores the loaded definitions on close. */
public final class WeaponFixture {
    private WeaponFixture() {}

    /** A weapon with one projectile group: min–max damage, projectiles per shot and rate of fire. */
    public static AutoCloseable install(int id, int min, int max, int numProj, float rof) {
        return install(id, bullet(min, max, numProj, rof));
    }

    static AutoCloseable install(int id, Bullet... bullets) {
        Weapon weapon = new Weapon(); weapon.id = id;
        for (Bullet bullet : bullets) weapon.bullets.add(bullet);
        HashMap<Integer, Weapon> before = Equip.weapons, next = new HashMap<>(before);
        next.put(id, weapon);
        Equip.weapons = next;
        return () -> Equip.weapons = before;
    }

    static Bullet bullet(int min, int max, int numProj, float rof) {
        Bullet bullet = new Bullet(); bullet.min = min; bullet.max = max; bullet.numProj = numProj; bullet.rof = rof;
        return bullet;
    }
}
