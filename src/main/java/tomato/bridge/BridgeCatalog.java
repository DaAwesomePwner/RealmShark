package tomato.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** CSV is the bot's allowlist, never the export destination. */
public final class BridgeCatalog {
    private final Set<String> names;
    private BridgeCatalog(Set<String> names) { this.names = Collections.unmodifiableSet(names); }
    public static BridgeCatalog empty() { return new BridgeCatalog(new HashSet<>()); }
    public int size() { return names.size(); }
    public boolean contains(BridgePayload.Item item) { return names.contains(normalize(item.rawName)) || names.contains(normalize(item.baseName)); }
    // Same normalization as LastEternity/RealmShark tomato_integration SendLoot (MIT).
    public static String normalize(String name) {
        return name.replaceAll("[\u2018\u2019\u02bc\u2032\u00b4`]", "'")
            .replaceAll("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]", "-")
            .replaceAll("\\s*-\\s*", "-").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
    public static BridgeCatalog load(Path path) throws IOException {
        if (Files.size(path)>16*1024*1024) throw new IOException("Loot CSV exceeds 16 MB.");
        Set<String> names = new HashSet<>();
        try (PushbackReader r = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first=r.read();if(first!=-1&&first!='\uFEFF')r.unread(first);
            List<String> header = row(r); int index = -1;
            if (header != null) for(int i=0;i<header.size();i++) if ("Item Name".equalsIgnoreCase(header.get(i).replace("\uFEFF", "").trim())) index=i;
            if (index<0) throw new IOException("Loot CSV is missing the Item Name header.");
            List<String> cells;
            while ((cells=row(r))!=null) {
                if(cells.size()==1 && cells.get(0).trim().isEmpty()) continue;
                if(index>=cells.size()) throw new IOException("Loot CSV contains an incomplete row.");
                String value=normalize(cells.get(index)); if(!value.isEmpty()) names.add(value);
            }
        }
        if(names.isEmpty()) throw new IOException("Loot CSV has no item names.");
        return new BridgeCatalog(names);
    }
    private static List<String> row(PushbackReader r) throws IOException {
        List<String> cells=new ArrayList<>(); StringBuilder cell=new StringBuilder(); boolean quoted=false, any=false;
        int c;
        while((c=r.read())!=-1) {
            any=true;
            if(c=='"') {
                if(quoted) { int next=r.read(); if(next=='"') cell.append('"'); else { quoted=false; if(next!=-1) r.unread(next); } }
                else if(cell.toString().trim().isEmpty()) quoted=true;
                else throw new IOException("Malformed quote in loot CSV.");
            } else if(c==',' && !quoted) { cells.add(cell.toString().trim()); cell.setLength(0); }
            else if((c=='\n'||c=='\r') && !quoted) { if(c=='\r') {int next=r.read(); if(next!=-1&&next!='\n') r.unread(next);} cells.add(cell.toString().trim()); return cells; }
            else cell.append((char)c);
        }
        if(quoted) throw new IOException("Unclosed quoted field in loot CSV.");
        if(!any) return null;
        cells.add(cell.toString().trim()); return cells;
    }
}
