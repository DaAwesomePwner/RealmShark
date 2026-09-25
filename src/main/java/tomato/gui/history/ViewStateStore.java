package tomato.gui.history;

import com.google.gson.*;
import util.PreferencesStore;
import util.PropertiesManager;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** One versioned preference document per module; the existing coalescing writer owns disk I/O. */
public final class ViewStateStore {
    public interface Storage {
        String get(String key);
        CompletionStage<PreferencesStore.SaveResult> put(String key,String value);
    }
    private final Storage storage;
    public ViewStateStore(Storage storage){this.storage=Objects.requireNonNull(storage);}
    public static ViewStateStore application(){return new ViewStateStore(new Storage(){
        public String get(String key){return PropertiesManager.getProperty(key);}
        public CompletionStage<PreferencesStore.SaveResult> put(String key,String value){return PropertiesManager.setPropertiesAsync(key,value);}
    });}
    public static ViewStateStore preferences(PreferencesStore preferences){return new ViewStateStore(new Storage(){
        public String get(String key){return preferences.getProperty(key);}
        public CompletionStage<PreferencesStore.SaveResult> put(String key,String value){return preferences.setProperties(key,value);}
    });}
    private String key(String module){if(!module.matches("[a-z][a-z0-9-]*"))throw new IllegalArgumentException("Invalid workspace key");return "ux.archive."+module;}
    private JsonObject document(String module){
        String value=storage.get(key(module));
        if(value==null||value.isEmpty()){JsonObject fresh=new JsonObject();fresh.addProperty("version",1);fresh.add("named",new JsonObject());return fresh;}
        try {
            JsonObject json=JsonParser.parseString(value).getAsJsonObject();
            if(json.get("version").getAsInt()!=1||!json.get("named").isJsonObject())throw new IllegalArgumentException("Unsupported workspace document");
            return json;
        }catch(RuntimeException failure){throw new IllegalArgumentException("Saved workspace cannot be read; Reset saved state to replace it",failure);}
    }
    public synchronized <F,S extends Enum<S>> ViewState<F,S> load(String module,ViewState<F,S> defaults){
        JsonObject json=document(module);return json.has("last")?defaults.restore(json.getAsJsonObject("last")):defaults;
    }
    public synchronized CompletionStage<PreferencesStore.SaveResult> save(String module,ViewState<?,?> state){
        JsonObject json=document(module);json.add("last",state.toJson());return storage.put(key(module),json.toString());
    }
    public synchronized List<String> names(String module){
        List<String> names=new ArrayList<>(document(module).getAsJsonObject("named").keySet());Collections.sort(names);return Collections.unmodifiableList(names);
    }
    public synchronized CompletionStage<PreferencesStore.SaveResult> saveNamed(String module,String name,ViewState<?,?> state){
        if(name==null||name.trim().isEmpty()||name.length()>100)throw new IllegalArgumentException("Name must contain 1–100 characters");
        JsonObject json=document(module);json.getAsJsonObject("named").add(name.trim(),state.toJson());return storage.put(key(module),json.toString());
    }
    public synchronized <F,S extends Enum<S>> ViewState<F,S> loadNamed(String module,String name,ViewState<F,S> defaults){
        JsonElement value=document(module).getAsJsonObject("named").get(name);
        if(value==null)throw new IllegalArgumentException("Saved view is no longer available");return defaults.restore(value.getAsJsonObject());
    }
    public synchronized CompletionStage<PreferencesStore.SaveResult> deleteNamed(String module,String name){
        JsonObject json=document(module);json.getAsJsonObject("named").remove(name);return storage.put(key(module),json.toString());
    }
    public synchronized CompletionStage<PreferencesStore.SaveResult> reset(String module){return storage.put(key(module),"");}
}
