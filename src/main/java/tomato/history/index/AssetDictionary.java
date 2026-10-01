package tomato.history.index;

import assets.AssetCache;
import assets.IdToAsset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import tomato.history.AppHistory;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.CharacterClass;

/** Asset access is confined to a snapshot on the index writer, never one file read per record. */
public final class AssetDictionary {
    record Loaded(IndexDictionary dictionary,int objectNames,int enchantNames,int classNames) {}
    private AssetDictionary() {}

    public static void changed() {
        CompletableFuture.runAsync(() -> {
            HistoryIndex index=AppHistory.index();
            if (index!=null) index.dictionaryChanged();
        });
    }

    static IndexDictionary snapshot() {
        return load().dictionary();
    }

    static Loaded load() {
        String stamp=AssetCache.stamp();
        // Class initialization loads from AssetCache.root(), including the legacy assets/ layout without a pointer.
        Map<Integer,String> objects=IdToAsset.objectNames();
        Map<Integer,String> classes=new HashMap<>();
        for (CharacterClass cls:CharacterClass.CHAR_CLASS_LIST) classes.put(cls.id,cls.name);
        return load(stamp,objects,classes,ParseEnchants.ENCHANT_DEFINITIONS);
    }

    static Loaded load(String stamp,Map<Integer,String> objects,Map<Integer,String> classes,
                       Map<Short,ParseEnchants.Definition> definitions) {
        return new Loaded(snapshot(stamp,objects,classes,definitions),objects.size(),definitions.size(),classes.size());
    }

    static IndexDictionary snapshot(String stamp,Map<Integer,String> objects,Map<Integer,String> classes,
                                    Map<Short,ParseEnchants.Definition> definitions) {
        if (objects.isEmpty() || classes.isEmpty() || definitions.isEmpty()) return IndexDictionary.NONE;
        Map<Integer,String> objectNames=Map.copyOf(objects), classNames=Map.copyOf(classes);
        Map<Short,ParseEnchants.Definition> enchants=Map.copyOf(definitions);
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        add(digest,stamp==null || stamp.isBlank()?"":stamp);
        // Sorting and length framing make the version independent of map order and text delimiters.
        for (Map.Entry<Short,ParseEnchants.Definition> entry:new TreeMap<>(enchants).entrySet()) {
            add(digest,Short.toString(entry.getKey())); add(digest,entry.getValue().displayName()); add(digest,entry.getValue().description());
        }
        add(digest,"objects"); digestNames(digest,objectNames);
        add(digest,"classes"); digestNames(digest,classNames);
        String version="assets-v1:"+HexFormat.of().formatHex(digest.digest());
        return new IndexDictionary() {
            public String version() { return version; }
            public String objectName(int id) { return objectNames.get(id); }
            public String className(int id) { return classNames.get(id); }
            public String enchantName(int id) {
                ParseEnchants.Definition definition=id<0 || id>65535?null:enchants.get((short)id);
                return definition==null?null:definition.displayName();
            }
        };
    }
    private static void digestNames(MessageDigest digest,Map<Integer,String> names) {
        for (Map.Entry<Integer,String> entry:new TreeMap<>(names).entrySet()) {
            add(digest,Integer.toString(entry.getKey())); add(digest,entry.getValue());
        }
    }
    private static void add(MessageDigest digest,String value) {
        byte[] bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length+":").getBytes(StandardCharsets.UTF_8)); digest.update(bytes);
    }
}
