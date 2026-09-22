package tomato.gui.chat;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Permission;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.TomatoData;
import tomato.gui.history.*;
import tomato.gui.keypop.KeypopGUI;
import tomato.history.SessionStore;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class SocialScratchTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void injectedProfileScratchWorksWithWriteProtectedCwdAndCapturedHistory() throws Exception {
        Path cwd=temp.newFolder("application").toPath(),profile=temp.newFolder("user-history").toPath(),output=temp.getRoot().toPath().resolve("probe.log");
        Set<String> classpath=new LinkedHashSet<>();
        for(String entry:System.getProperty("java.class.path").split(File.pathSeparator))classpath.add(new File(entry).getAbsolutePath());
        for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())
            if(loader instanceof URLClassLoader)for(URL url:((URLClassLoader)loader).getURLs())if("file".equals(url.getProtocol()))classpath.add(new File(url.toURI()).getAbsolutePath());
        for(Class<?> type:new Class<?>[]{getClass(),ChatGUI.class})classpath.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath());
        Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/"+(File.separatorChar=='\\'?"java.exe":"java")).getAbsolutePath(),
            "-Djava.awt.headless=true","-Djava.util.prefs.PreferencesFactory=util.InMemoryPreferencesFactory","-cp",String.join(File.pathSeparator,classpath),Probe.class.getName(),profile.toString())
            .directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            boolean ended=child.waitFor(45,TimeUnit.SECONDS);if(!ended){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
            String transcript=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);
            assertTrue(transcript,ended);assertEquals(transcript,0,child.exitValue());assertTrue(transcript,transcript.contains("SOCIAL_SCRATCH_OK"));
        } finally { if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);} }
    }
    /** A separate headless JVM enforces read-only paths without changing workstation ACLs. */
    public static final class Probe {
        @SuppressWarnings("removal")
        public static void main(String[] args) throws Exception {
            Path profile=Paths.get(args[0]).toAbsolutePath(),captured=profile.resolve("captured"),scratch=profile.resolve(".query-scratch"),cwd=Paths.get("").toAbsolutePath();
            try(SessionStore source=new SessionStore(captured,true,"synthetic")){
                source.append("chat",new ChatMessage(LocalDateTime.of(2026,9,22,12,0),ChatMessage.Channel.WORLD,"Ann","","Ann","Synthetic saved message",""));
                JsonObject pop=new JsonObject();pop.addProperty("time","2026-09-22T12:00:00Z");pop.addProperty("player","Ann");pop.addProperty("item","Halls");pop.addProperty("kind","KEY");
                source.append("keypops",pop);source.flush();
            }
            AtomicInteger denied=new AtomicInteger();
            System.setSecurityManager(new SecurityManager(){
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkWrite(String file){check(file);}
                @Override public void checkDelete(String file){check(file);}
                private void check(String file){Path path=Paths.get(file).toAbsolutePath().normalize();if(path.startsWith(cwd)||path.startsWith(captured)){denied.incrementAndGet();throw new SecurityException("Synthetic read-only location");}}
            });
            try(SessionStore store=new SessionStore(captured,false,"reader")){
                for(Path path:Arrays.asList(cwd.resolve("denied"),captured.resolve("denied"))){try{Files.write(path,new byte[]{1});throw new AssertionError("Read-only guard not effective");}catch(SecurityException expected){}}
                ViewStateStore states=new ViewStateStore(new ViewStateStore.Storage(){
                    final Map<String,String> values=new HashMap<>();public String get(String key){return values.get(key);}
                    public CompletionStage<PreferencesStore.SaveResult> put(String key,String value){values.put(key,value);return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1));}
                });
                ArchiveWorkspace<?,?,?> chat=edt(()->(ArchiveWorkspace<?,?,?>)new ChatGUI(new TomatoData(),new ChatFilters(),false).workspace(store,scratch.resolve("chat"),states));
                ArchiveWorkspace<?,?,?> pops=edt(()->(ArchiveWorkspace<?,?,?>)new KeypopGUI().workspace(store,scratch.resolve("keypops"),states));
                try {
                    edt(()->{chat.selectSession(SessionStore.ALL);pops.selectSession(SessionStore.ALL);return null;});
                    await(()->chat.displayedPage()!=null&&pops.displayedPage()!=null&&!chat.loading()&&!pops.loading());
                    assertEquals(1,edt(()->chat.displayedPage().matches).longValue());assertEquals(1,edt(()->pops.displayedPage().matches).longValue());
                    try(Stream<Path> files=Files.walk(scratch)){
                        Set<Path> pins=new HashSet<>();files.filter(p->p.getFileName().toString().startsWith("archive-pin-")).forEach(pins::add);
                        assertEquals("Each query has its own history-profile pin",2,pins.size());for(Path pin:pins)assertTrue(pin.toAbsolutePath().startsWith(profile));
                    }
                    assertEquals("Factories must not try CWD or captured-data writes",2,denied.get());
                } finally { edt(()->{chat.close();pops.close();return null;}); }
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean clean=false;
                while(System.nanoTime()<until){try(Stream<Path> files=Files.walk(scratch)){clean=files.noneMatch(p->p.getFileName().toString().startsWith("archive-pin-")||p.getFileName().toString().startsWith("archive-result-"));}if(clean)break;Thread.sleep(20);}
                assertTrue("Owned query scratch is cleaned",clean);System.out.println("SOCIAL_SCRATCH_OK");
            } finally { System.setSecurityManager(null); }
        }
    }
}
