package tomato.gui.stats;

import assets.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Reject a scan spanning two published asset generations; projected values remain frozen thereafter. */
final class ArchiveDefinitions {
    private final Supplier<Path> root;
    private final Path generation;
    ArchiveDefinitions(){this(AssetCache::root);}
    ArchiveDefinitions(Supplier<Path> root){this.root=root;synchronized(ImageBuffer.class){generation=root.get();}}
    void check()throws IOException{if(!generation.equals(root.get()))throw new IOException("Asset definitions changed during this archive query. Refresh to capture one generation; no mixed result was applied.");}
    String canonical(String name)throws IOException{synchronized(ImageBuffer.class){check();return LootQuery.canonical(name);}}
    boolean dungeon(String name)throws IOException{synchronized(ImageBuffer.class){check();return tomato.realmshark.ParseDungeon.isDungeon(name);}}
    String objectName(int id)throws IOException{synchronized(ImageBuffer.class){check();String name=IdToAsset.objectName(id);LootArchiveAdapter.label(name);return name==null?"Object #"+id:name;}}
    String description(){String name=generation.getFileName().toString();return name.startsWith("generation-")?name:"legacy assets (published-generation guard; projected labels frozen)";}
}
