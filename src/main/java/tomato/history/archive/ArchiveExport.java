package tomato.history.archive;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import tomato.history.SessionStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Streaming exports with an embedded revision manifest and exclusive destination claims. */
public final class ArchiveExport {
    public enum Format { JSON, CSV }
    public static final class Column<R> {
        public final String name;public final Function<R,?> value;
        public Column(String name,Function<R,?> value){this.name=Objects.requireNonNull(name);this.value=Objects.requireNonNull(value);}
    }
    private ArchiveExport(){ }
    /** The caller owns lease and closes it even if the worker is cancelled before starting. */
    public static <R> Path write(ArchiveResult.Lease<R> lease,ExportSelection selection,Format format,Path folder,
            String base,List<Column<R>> columns,Cancellation cancel)throws IOException {
        ArchiveIO.offEdt();cancel.check();Files.createDirectories(folder);
        if(base==null||!base.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,100}"))throw new IllegalArgumentException("Invalid export filename");
        Path staged=Files.createTempFile(folder,".archive-export-",".tmp");
        try {
            JsonObject manifest=lease.manifest();manifest.addProperty("exportScope",selection.kind.name());
            manifest.addProperty("exportCount",selection.expected(lease.matches()));
            if(selection.kind==ExportSelection.Kind.PAGE){manifest.addProperty("page",selection.page);manifest.addProperty("pageSize",selection.size);}
            if(selection.kind==ExportSelection.Kind.SELECTED)manifest.add("selected",SessionStore.JSON.toJsonTree(new TreeSet<>(selection.refs)));
            try(Writer output=Files.newBufferedWriter(staged,StandardCharsets.UTF_8)) {
                if(format==Format.JSON) {
                    JsonWriter json=new JsonWriter(output);json.beginObject();json.name("manifest");SessionStore.JSON.toJson(manifest,json);
                    json.name("rows").beginArray();
                    lease.stream(selection,row->{json.beginObject();json.name("origin");SessionStore.JSON.toJson(row.ref,ArchiveRow.Ref.class,json);
                        json.name("value");SessionStore.JSON.toJson(row.value,row.value.getClass(),json);json.endObject();},cancel);
                    json.endArray().endObject();json.flush();
                }else{
                    output.write("# RealmShark archive manifest,"+csv(manifest.toString())+"\r\n");
                    output.write("Session,Module,Locator,Child");
                    if(columns.isEmpty())output.write(",Record JSON");else for(Column<R> column:columns)output.write(","+csv(column.name));
                    output.write("\r\n");
                    lease.stream(selection,row->{output.write(csv(row.ref.session)+","+csv(row.ref.module)+","+csv(row.ref.locator)+","+csv(row.ref.child));
                        if(columns.isEmpty())output.write(","+csv(SessionStore.JSON.toJson(row.value)));
                        else for(Column<R> column:columns)output.write(","+csv(Objects.toString(column.value.apply(row.value),"")));
                        output.write("\r\n");},cancel);
                }
            }
            cancel.check();
            for(long suffix=1;;suffix++) {
                cancel.check();Path target=folder.resolve(base+(suffix==1?"":" ("+suffix+")")+"."+format.name().toLowerCase(Locale.ROOT));
                OutputStream claim;
                try {claim=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
                catch(IOException failure){if(failure instanceof FileAlreadyExistsException||Files.exists(target,LinkOption.NOFOLLOW_LINKS))continue;throw failure;}
                try(OutputStream output=claim;InputStream input=Files.newInputStream(staged)) {
                    byte[] buffer=new byte[64*1024];int n;while((n=input.read(buffer))>=0){cancel.check();output.write(buffer,0,n);}cancel.check();
                }catch(IOException|RuntimeException|Error failure){try{Files.deleteIfExists(target);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
                return target;
            }
        }finally{Files.deleteIfExists(staged);}
    }
    private static String csv(String value){String trimmed=value.trim();if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0)value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
}
