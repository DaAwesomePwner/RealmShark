package assets;

import com.google.flatbuffers.FlatBufferBuilder;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import tomato.backend.data.AbilityScalingManager;
import util.PropertiesManager;

/** Real generated XML, FlatBuffers and PNG files in the isolated test working directory. */
public final class AssetGenerationFixture implements AutoCloseable {
    public static final int ITEM = 0x1234;
    private final Path root = Paths.get("assets"), source;
    private final List<Runnable> restore = new ArrayList<>();
    private final String oldPath = PropertiesManager.getProperty("realmResPath"), oldStamp = PropertiesManager.getProperty("lastModifiedTime");

    public AssetGenerationFixture() throws Exception {
        if (Files.exists(root)) throw new AssertionError("Fixture requires an asset-free isolated working directory");
        save(AssetCache.class, "current", null);
        save(AssetExtractor.class, "REALM_RES_PATH", null); save(AssetExtractor.class, "explicitPath", null);
        save(IdToAsset.class, "objectID", null); save(IdToAsset.class, "tileID", null);
        save(SpriteFlatBuffer.class, "sprites", null); save(SpriteFlatBuffer.class, "notLoaded", null);
        save(SpriteJson.class, "sprites", null); save(SpriteJson.class, "animatedSprites", null); save(SpriteJson.class, "notLoaded", null);
        save(tomato.gui.myinfo.Equip.class, "weapons", null);
        save(tomato.realmshark.ParseEquipment.class, "EQUIPMENT", null);
        save(tomato.realmshark.ParseDungeon.class, "CATALOG", null);
        for (String field : new String[]{"CHAR_CLASS_LIST", "CHARACTER_CLASS", "CLASS_NAME", "CLASS_MAX_STATS", "WEAPON_CLASSES", "CHARACTER_IDS"})
            save(tomato.realmshark.enums.CharacterClass.class, field, null);
        for (String field : new String[]{"ENCHANTS", "ENCHANT_EFFECTS", "ENCHANT_REGEN", "ENCHANT_LOOT_BONUS"})
            save(tomato.realmshark.ParseEnchants.class, field, null);
        save(AbilityScalingManager.class, "rules", AbilityScalingManager.getInstance());
        source = Files.createTempFile(Paths.get("."), "synthetic-atlas-", ".assets");
    }
    private void save(Class<?> type, String name, Object receiver) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); Object value = field.get(receiver);
        restore.add(() -> { try { field.set(receiver, value); } catch (IllegalAccessException e) { throw new AssertionError(e); } });
    }
    public void install(boolean blue) throws Exception { install(blue, message -> {}); }
    public void install(boolean blue, java.util.function.Consumer<String> progress) throws Exception {
        AssetExtractor.recover(source.toFile(), blue ? "blue" : "red", progress, (input, folders) -> write(folders, blue));
    }
    public void removeSource() throws Exception { Files.delete(source); }

    private static void write(File[] folders, boolean blue) throws java.io.IOException {
        for (File folder : folders) Files.createDirectories(folder.toPath());
        String sheet = blue ? "blue-sheet" : "red-sheet";
        int index = blue ? 1 : 0;
        Path xml = folders[2].toPath();
        String texture = "<Texture><File>" + sheet + "</File><Index>" + index + "</Index></Texture>";
        Files.write(xml.resolve("equip.xml"), ("<Objects><Object type='0x1234' id='Synthetic item'><Class>Equipment</Class>" + texture
            + "<Projectile id='0'><MinDamage>10</MinDamage><MaxDamage>20</MaxDamage></Projectile></Object>"
            + "<Ground type='1' id='Synthetic ground'>" + texture + "</Ground></Objects>").getBytes(StandardCharsets.UTF_8));
        Files.write(xml.resolve("players.xml"), ("<Objects><Object type='0x7ffe' id='Synthetic class'><Equipment>1,2,3</Equipment>"
            + "<MaxHitPoints max='100'>10</MaxHitPoints></Object></Objects>").getBytes(StandardCharsets.UTF_8));
        Files.write(xml.resolve("enchantments.xml"), "<Enchantments/>".getBytes(StandardCharsets.UTF_8));
        BufferedImage atlas = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++)
            atlas.setRGB(x, y, (blue ? (x < 2 ? Color.GREEN : Color.BLUE) : (x < 2 ? Color.RED : Color.YELLOW)).getRGB());
        ImageIO.write(atlas, "png", folders[1].toPath().resolve("groundTiles.png").toFile());

        FlatBufferBuilder builder = new FlatBufferBuilder();
        int name = builder.createString(sheet);
        assets.flattbuffer.Sprite.startSprite(builder);
        assets.flattbuffer.Sprite.addSpriteSheetName(builder, name);
        assets.flattbuffer.Sprite.addAId(builder, 1);
        assets.flattbuffer.Sprite.addIndex(builder, index);
        assets.flattbuffer.Sprite.addPosition(builder, assets.flattbuffer.Position.createPosition(builder, blue ? 2 : 0, 0, 2, 2));
        assets.flattbuffer.Sprite.addMostCommonColor(builder, assets.flattbuffer.Color.createColor(builder, blue ? 0 : 1, 0, blue ? 1 : 0, 1));
        int sprite = assets.flattbuffer.Sprite.endSprite(builder);
        int sprites = assets.flattbuffer.SpriteSheet.createSpritesVector(builder, new int[]{sprite});
        int spriteSheet = assets.flattbuffer.SpriteSheet.createSpriteSheet(builder, name, 1, sprites);
        int sheets = assets.flattbuffer.SpriteSheetRoot.createSpritesVector(builder, new int[]{spriteSheet});
        int offset = assets.flattbuffer.SpriteSheetRoot.createSpriteSheetRoot(builder, sheets, 0);
        assets.flattbuffer.SpriteSheetRoot.finishSpriteSheetRootBuffer(builder, offset);
        Files.write(folders[0].toPath().resolve("spritesheetf"), builder.sizedByteArray());
    }

    @Override public void close() throws Exception {
        synchronized (ImageBuffer.class) { restore.forEach(Runnable::run); ImageBuffer.clear(); }
        PropertiesManager.setProperties("realmResPath", oldPath == null ? "" : oldPath);
        PropertiesManager.setProperties("lastModifiedTime", oldStamp == null ? "" : oldStamp);
        Files.deleteIfExists(source);
        if (Files.exists(root)) try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
