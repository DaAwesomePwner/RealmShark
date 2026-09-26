package tomato.gui.search;

import java.util.*;
import java.util.function.*;

/** A navigation/focus callback, never a toggle, save, capture or delivery command. */
public final class ActionDescriptor {
    public final String id,label,keywords,location,persistence,preview,disabledReason;
    private final BooleanSupplier enabled;
    private final Runnable navigate;
    public ActionDescriptor(String id,String label,String keywords,String location,String persistence,String preview,
            BooleanSupplier enabled,String disabledReason,Runnable navigate){
        if(id==null||!id.matches("[a-z0-9.-]+"))throw new IllegalArgumentException("Stable action ID required");
        this.id=id;this.label=Objects.requireNonNull(label);this.keywords=Objects.requireNonNull(keywords);
        this.location=Objects.requireNonNull(location);this.persistence=Objects.requireNonNull(persistence);this.preview=Objects.requireNonNull(preview);
        this.enabled=Objects.requireNonNull(enabled);this.disabledReason=Objects.requireNonNull(disabledReason);this.navigate=Objects.requireNonNull(navigate);
    }
    public boolean enabled(){return enabled.getAsBoolean();}
    public boolean open(){if(!enabled())return false;navigate.run();return true;}
    public boolean matches(String query){String haystack=(label+" "+keywords+" "+location+" "+id).toLowerCase(Locale.ROOT);
        for(String word:query.toLowerCase(Locale.ROOT).trim().split("\\s+"))if(!haystack.contains(word))return false;return true;}
    public String details(){return label+"\nLocation: "+location+"\nPersistence: "+persistence+"\nPreview: "+preview+"\n"+(enabled()?"Open navigates to the existing control. Changes require its normal controls.":"Unavailable: "+disabledReason);}
    @Override public String toString(){return label+" — "+location+(enabled()?"":" (unavailable)");}
}
