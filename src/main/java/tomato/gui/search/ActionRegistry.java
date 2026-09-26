package tomato.gui.search;

import java.util.*;

/** Explicit catalog of audited navigation callbacks. */
public final class ActionRegistry {
    private static final ActionRegistry APPLICATION=new ActionRegistry();
    public static ActionRegistry application(){return APPLICATION;}
    private final Map<String,ActionDescriptor> entries=new LinkedHashMap<>();
    public synchronized void register(ActionDescriptor value){entries.put(value.id,value);}
    public synchronized void clear(){entries.clear();}
    public synchronized List<ActionDescriptor> search(String query){List<ActionDescriptor> result=new ArrayList<>();
        for(ActionDescriptor entry:entries.values())if(entry.matches(query))result.add(entry);
        result.sort(Comparator.comparing(entry->entry.label,String.CASE_INSENSITIVE_ORDER));return Collections.unmodifiableList(result);}
}
