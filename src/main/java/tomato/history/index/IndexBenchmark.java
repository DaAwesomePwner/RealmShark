package tomato.history.index;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tomato.history.SessionStore;

/** Read-only history benchmark. The fresh output database must be outside the history tree. */
public final class IndexBenchmark {
    private IndexBenchmark() {}
    public static void main(String[] args) throws Exception {
        if (args.length!=2) throw new IllegalArgumentException("Usage: IndexBenchmark <historyDir> <indexFile>");
        Path history=Path.of(args[0]).toRealPath(), requested=Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor=requested.getParent();
        while (ancestor!=null && !Files.exists(ancestor)) ancestor=ancestor.getParent();
        if (ancestor==null) throw new IllegalArgumentException("Index output needs an existing ancestor");
        Path output=ancestor.toRealPath().resolve(ancestor.relativize(requested)).normalize();
        if (output.startsWith(history)) throw new IllegalArgumentException("Benchmark output must be outside historyDir");
        for (String suffix:List.of("","-wal","-shm")) if (Files.exists(Path.of(output+suffix)))
            throw new IllegalArgumentException("Benchmark requires a fresh indexFile");
        // Explicit override keeps test/benchmark DLL extraction with the benchmark output, never inside historyDir.
        String previous=System.getProperty("realmshark.indexNativeDir");
        Path natives=output.resolveSibling(output.getFileName()+"-native");
        if (natives.startsWith(history) || Files.exists(natives) && natives.toRealPath().startsWith(history))
            throw new IllegalArgumentException("Native extraction must be outside historyDir");
        System.setProperty("realmshark.indexNativeDir",natives.toString());
        try (SessionStore store=new SessionStore(history,false,"index-benchmark")) {
            HistoryIndex index=new HistoryIndex(store,output,true);
            try {
                long start=System.nanoTime();
                HistoryIndex.State state=index.start().get(30,TimeUnit.MINUTES);
                if (state.phase()!=HistoryIndex.Phase.READY) throw new IllegalStateException(state.reason());
                System.out.printf(Locale.ROOT,"Backfill: %.1f ms%n",(System.nanoTime()-start)/1e6);
                for (Map.Entry<String,Long> count:index.rowCounts().entrySet()) System.out.println(count.getKey()+": "+count.getValue());
                String player="", session="", visit="";
                try (Connection c=index.readConnection(); Statement s=c.createStatement()) {
                    try (ResultSet r=s.executeQuery("SELECT name FROM players ORDER BY occurrences DESC,key LIMIT 1")) { if (r.next()) player=r.getString(1); }
                    try (ResultSet r=s.executeQuery("SELECT s.id,v.visit_id FROM runs r JOIN sessions s ON s.sid=r.sid JOIN visits v ON v.vid=r.vid WHERE r.sid=(SELECT sid FROM runs GROUP BY sid ORDER BY count(*) DESC,sid LIMIT 1) ORDER BY r.started DESC,r.id LIMIT 1")) {
                        if (r.next()) { session=r.getString(1); visit=r.getString(2); }
                    }
                }
                String chosenPlayer=player, chosenSession=session, chosenVisit=visit;
                measure("search oryx",() -> index.search("oryx",Set.of(),50));
                if (!player.isEmpty()) measure("search frequent player",() -> index.search(chosenPlayer,Set.of(),50));
                else System.out.println("search frequent player: unavailable (no indexed players)");
                measure("first runsPage",() -> index.runsPage(HistoryIndex.RunFilter.all(),HistoryIndex.RunSort.NEWEST,0,50));
                if (!visit.isEmpty()) measure("visitFacts",() -> index.visitFacts(chosenSession,chosenVisit));
                else System.out.println("visitFacts: unavailable (no indexed runs)");
                long failed=0, skipped=0;
                for (SessionStore.SessionEntry entry:store.catalog()) {
                    HistoryIndex.SessionState readiness=index.sessionState(entry.id);
                    if (readiness.readiness()==HistoryIndex.Readiness.FAILED) failed++;
                    skipped+=readiness.skipped();
                }
                System.out.println("Failed sessions: "+failed+"; skipped records: "+skipped);
            } finally { index.closeAsync().get(30,TimeUnit.SECONDS); }
            long bytes=Files.size(output);
            for (String suffix:List.of("-wal","-shm")) if (Files.exists(Path.of(output+suffix))) bytes+=Files.size(Path.of(output+suffix));
            System.out.println("DB size (including any remaining sidecars): "+bytes+" bytes");
        } finally {
            if (previous==null) System.clearProperty("realmshark.indexNativeDir"); else System.setProperty("realmshark.indexNativeDir",previous);
        }
    }
    @FunctionalInterface private interface Query { Object run() throws Exception; }
    private static void measure(String name,Query query) throws Exception {
        long[] elapsed=new long[5];
        for (int i=0;i<elapsed.length;i++) { long start=System.nanoTime(); query.run(); elapsed[i]=System.nanoTime()-start; }
        Arrays.sort(elapsed); System.out.printf(Locale.ROOT,"%s median of 5: %.3f ms%n",name,elapsed[2]/1e6);
    }
}
