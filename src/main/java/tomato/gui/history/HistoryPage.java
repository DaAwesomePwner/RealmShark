package tomato.gui.history;

import tomato.history.SessionStore;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Streams an archive search while retaining only one displayed page in memory. */
public final class HistoryPage<T> {
    public static final int SIZE = 1000;
    public final List<T> values = new ArrayList<>();
    public long matches;
    public final int page;
    private final int size;
    private HistoryPage(int page,int size){this.page=page;this.size=size;}
    /** Opt-in global query path. Existing loader lambdas retain their original signatures. */
    public static <R,F,S extends Enum<S>> tomato.history.archive.ArchiveResult<R> open(SessionStore store,
            tomato.history.archive.ArchiveQuery<F,S> query,tomato.history.archive.ArchiveAdapter<R,F,S> adapter,
            java.nio.file.Path scratch,tomato.history.archive.Cancellation cancel)throws IOException {
        return tomato.history.archive.ArchiveResult.open(store,query,adapter,scratch,cancel);
    }
    public static <T> HistoryPage<T> read(SessionStore store,String scope,String module,Class<T> type,int page,String query,Function<T,String> text)throws IOException{
        return read(store,scope,module,type,page,SIZE,query,text);
    }
    public static <T> HistoryPage<T> read(SessionStore store,String scope,String module,Class<T> type,int page,int size,String query,Function<T,String> text)throws IOException{
        return read(store,scope,module,type,page,size,query,text,value->true);
    }
    public static <T> HistoryPage<T> read(SessionStore store,String scope,String module,Class<T> type,int page,int size,String query,Function<T,String> text,java.util.function.Predicate<T> include)throws IOException{
        HistoryPage<T> result=new HistoryPage<>(page,size);String search=query.trim().toLowerCase(Locale.ROOT);long start=(long)page*size;
        store.read(scope,module,type,(session,value)->{
            if(!include.test(value))return;
            if(!text.apply(value).toLowerCase(Locale.ROOT).contains(search))return;
            long index=result.matches++;
            if(index>=start&&index<start+size)result.values.add(value);
        });return result;
    }
    public boolean more(){return matches>(long)(page+1)*size;}
    public String description(){return values.size()+" of "+matches+" matching records · page "+(page+1)+" · filters inside the view apply to this page";}
}
