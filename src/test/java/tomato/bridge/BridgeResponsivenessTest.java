package tomato.bridge;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.bridge.BridgeReviewGUI;
import java.awt.Component;
import java.awt.Container;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import static org.junit.Assert.*;

/** Barriers hold real service storage boundaries; deadlines detect lock coupling, not disk-speed budgets. */
public class BridgeResponsivenessTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private ExecutorService callers;
    private Path settings,csv;
    private BridgeCatalog catalog;
    private static final BridgeService.Transport NO_SEND=(u,j)->{throw new AssertionError("Unexpected HTTP submission");};

    @Before public void fixtures()throws Exception {
        callers=Executors.newFixedThreadPool(12);
        settings=temp.getRoot().toPath().resolve("bridge.properties");csv=temp.newFile("items.csv").toPath();
        Files.write(csv,"Item Name\nTest Sword\n".getBytes(StandardCharsets.UTF_8));catalog=BridgeCatalog.load(csv);
    }
    @After public void stopCallers()throws Exception {callers.shutdownNow();assertTrue(callers.awaitTermination(3,TimeUnit.SECONDS));}
    private BridgeConfig config(String name,boolean send){
        Properties p=new Properties();p.setProperty(BridgeConfig.PREFIX+"enabled","true");p.setProperty(BridgeConfig.PREFIX+"send",String.valueOf(send));
        p.setProperty(BridgeConfig.PREFIX+"endpoint","https://"+name+".invalid/ingest");p.setProperty(BridgeConfig.PREFIX+"guild_id","123456789012345678");
        p.setProperty(BridgeConfig.PREFIX+"link_token","isolated-test-token");p.setProperty(BridgeConfig.PREFIX+"csv_path",csv.toString());
        p.setProperty(BridgeConfig.PREFIX+"local_review_log",temp.getRoot().toPath().resolve("review.jsonl").toString());
        return new BridgeConfig(p);
    }
    private static BridgeConfig disabled(){return new BridgeConfig(new Properties());}
    private static List<BridgePayload.Drop> drop(){return Collections.singletonList(new BridgePayload.Drop(new BridgePayload.Item(42,"Test Sword","EQUIPMENT","UT","",false),7,"Fixture","Wizard","Test",false,false,1,0));}
    private BridgeService service(Storage storage,BridgeService.Transport transport,int capacity){BridgeService s=new BridgeService(settings,false,transport,capacity,storage);storage.owner.set(s);return s;}
    private Future<?> configure(BridgeService s,BridgeConfig config,boolean persist){return callers.submit(()->{s.configure(config,persist,false);return null;});}
    private static <T> T edt(Callable<T> action)throws Exception {FutureTask<T> task=new FutureTask<>(action);SwingUtilities.invokeLater(task);return task.get(2,TimeUnit.SECONDS);}
    private void responsive(BridgeService s,boolean active)throws Exception {
        long before=s.snapshot().observed;
        Future<?> capture=callers.submit(()->s.receive(drop()));
        assertNotNull(edt(s::snapshot)); // A timer-style read must finish before storage is released.
        assertTrue(edt(()->true)); // A subsequent EDT heartbeat must also run.
        capture.get(2,TimeUnit.SECONDS);assertEquals(before+(active?1:0),s.snapshot().observed);
    }
    private static void failed(Future<?> future)throws Exception {try{future.get(3,TimeUnit.SECONDS);fail("Expected configure failure");}catch(ExecutionException e){assertTrue(e.getCause().toString(),e.getCause() instanceof IOException);}}
    private static void close(BridgeService service)throws Exception {service.close();service.awaitClosed(3000);}
    private static Component find(Container root,String name){for(Component c:root.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container){Component found=find((Container)c,name);if(found!=null)return found;}}return null;}
    private static JTextField field(BridgeReviewGUI panel,String name){return (JTextField)Objects.requireNonNull(find(panel,"bridge-"+name));}
    private static JButton save(BridgeReviewGUI panel){return (JButton)Objects.requireNonNull(find(panel,"bridge-save"));}

    private static final class Gate {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        void pause()throws IOException {entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Test storage barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
        void reached()throws Exception {assertTrue("Storage boundary was not reached",entered.await(3,TimeUnit.SECONDS));}
        void open(){release.countDown();}
    }
    private final class Storage extends BridgeStorage {
        final AtomicReference<BridgeService> owner=new AtomicReference<>();
        final List<BridgeConfig> catalogs=new CopyOnWriteArrayList<>(),saved=new CopyOnWriteArrayList<>();
        final List<BridgeService.Review> audited=new CopyOnWriteArrayList<>();
        final AtomicInteger acquisitions=new AtomicInteger(),releases=new AtomicInteger();
        volatile BridgeConfig initial=disabled();
        volatile Gate startup,csvRead,save,acquire,cleanup,audit;
        volatile String auditStatus,failedCatalog,failedSave;
        volatile boolean failedSettings;
        void boundary(){assertFalse("Storage ran on EDT",SwingUtilities.isEventDispatchThread());BridgeService s=owner.get();if(s!=null)assertFalse("Storage held the model monitor",Thread.holdsLock(s));}
        void pause(Gate gate)throws IOException {boundary();if(gate!=null)gate.pause();}
        @Override BridgeConfig loadSettings(Path path)throws IOException {assertEquals(settings,path);pause(startup);if(failedSettings)throw new IOException("Fixture settings unavailable");return initial;}
        @Override BridgeCatalog loadCatalog(BridgeConfig next)throws IOException {catalogs.add(next);pause(csvRead);if(next.endpoint.equals(failedCatalog))throw new IOException("Fixture CSV unavailable");return catalog;}
        @Override void save(BridgeConfig next,Path path)throws IOException {assertEquals(settings,path);pause(save);if(next.endpoint.equals(failedSave))throw new IOException("Fixture save failed");saved.add(next);}
        @Override Closeable acquireLock(Path path)throws IOException {
            pause(acquire);Closeable lock=super.acquireLock(path);acquisitions.incrementAndGet();
            return ()->{try{pause(cleanup);}finally{lock.close();releases.incrementAndGet();}};
        }
        @Override void audit(BridgeService.Review entry,BridgeConfig next)throws IOException {pause(auditStatus==null||auditStatus.equals(entry.status)?audit:null);audited.add(entry);}
    }

    @Test public void edtConstructionReturnsDuringStartupAndLoadCompletionPreservesEachEditedField()throws Exception {
        Storage storage=new Storage();storage.initial=config("saved",true);Gate gate=storage.startup=new Gate();
        AtomicReference<BridgeService> ref=new AtomicReference<>();
        try {
            BridgeReviewGUI panel=edt(()->{BridgeService s=service(storage,NO_SEND,16);ref.set(s);return new BridgeReviewGUI(s);});
            BridgeService s=ref.get();gate.reached();assertTrue(s.snapshot().loading);responsive(s,false);
            edt(()->{
                assertTrue(((JLabel)find(panel,"bridge-state")).getText().contains("Loading"));assertFalse(save(panel).isEnabled());
                field(panel,"endpoint").setText("https://draft.invalid/ingest");field(panel,"guild").setText("draft");field(panel,"guild").setText("");
                JCheckBox enabled=(JCheckBox)find(panel,"bridge-enabled");enabled.setSelected(true);enabled.setSelected(false);
                ((JCheckBox)find(panel,"bridge-send")).setSelected(false);
                try{s.configure(disabled(),false,false);fail("EDT configure must not wait");}catch(IOException expected){}
                try{s.awaitReady(1000);fail("EDT readiness wait must be rejected");}catch(IllegalStateException expected){}
                return null;
            });
            gate.open();s.awaitReady(3000);
            edt(()->{
                panel.refresh();assertFalse(s.snapshot().loading);assertTrue(save(panel).isEnabled());
                assertEquals("https://draft.invalid/ingest",field(panel,"endpoint").getText());assertEquals("",field(panel,"guild").getText());
                assertEquals(storage.initial.csvPath,field(panel,"csv").getText());assertEquals(storage.initial.reviewLog,field(panel,"audit").getText());
                assertEquals(storage.initial.token,new String(((JPasswordField)field(panel,"token")).getPassword()));
                assertFalse(((JCheckBox)find(panel,"bridge-enabled")).isSelected());assertFalse(((JCheckBox)find(panel,"bridge-send")).isSelected());
                field(panel,"csv").setText("new draft");panel.refresh();assertEquals("new draft",field(panel,"csv").getText());return null;
            });
            assertEquals(1,s.snapshot().catalogSize);assertTrue(storage.saved.isEmpty());
        } finally {gate.open();if(ref.get()!=null)close(ref.get());}
    }

    @Test public void startupErrorsAreVisibleAndSavedFieldsRemainAvailableForCorrection()throws Exception {
        for(boolean settingsFailure:new boolean[]{true,false}) {
            Storage storage=new Storage();storage.initial=config("saved",false);storage.failedSettings=settingsFailure;storage.failedCatalog=storage.initial.endpoint;
            BridgeService s=service(storage,NO_SEND,16);
            try {
                s.awaitReady(3000);assertFalse(s.snapshot().loading);assertEquals("Settings need attention",s.snapshot().state);
                assertTrue(s.snapshot().logs.stream().anyMatch(l->l.level.equals("ERROR")));
                edt(()->{BridgeReviewGUI panel=new BridgeReviewGUI(s);assertTrue(save(panel).isEnabled());assertEquals(settingsFailure?"":storage.initial.endpoint,field(panel,"endpoint").getText());return null;});
                storage.failedCatalog=null;s.configure(config("corrected",false),false,false);responsive(s,true);s.awaitIdle(3000);
            } finally {close(s);}
        }
    }

    @Test public void blockedCsvAndSaveKeepPreviousConfigAndCaptureAndEdtResponsive()throws Exception {
        for(boolean saving:new boolean[]{false,true}) {
            Storage storage=new Storage();BridgeService s=service(storage,NO_SEND,16);Gate gate=new Gate();
            try {
                BridgeConfig old=config("old",false),next=config("next",false);s.configure(old,false,false);
                if(saving)storage.save=gate;else storage.csvRead=gate;
                Future<?> update=configure(s,next,true);gate.reached();assertSame(old,s.config());responsive(s,true);assertFalse(update.isDone());
                gate.open();update.get(3,TimeUnit.SECONDS);assertSame(next,s.config());assertEquals(Collections.singletonList(next),storage.saved);s.awaitIdle(3000);
            } finally {gate.open();close(s);}
        }
    }

    @Test public void startupAndConcurrentConfigurationsCannotPublishOutOfOrder()throws Exception {
        Storage storage=new Storage();storage.initial=config("startup",false);Gate startup=storage.startup=new Gate(),saveGate=new Gate();BridgeService s=service(storage,NO_SEND,16);
        try {
            startup.reached();BridgeConfig first=config("first",false),second=config("second",false);storage.save=saveGate;
            Future<?> a=configure(s,first,true);responsive(s,false);startup.open();saveGate.reached();assertSame(storage.initial,s.config());
            Future<?> b=configure(s,second,true);responsive(s,true);saveGate.open();a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);
            assertEquals(Arrays.asList(storage.initial,first,second),storage.catalogs);assertEquals(Arrays.asList(first,second),storage.saved);assertSame(second,s.config());s.awaitIdle(3000);
        } finally {startup.open();saveGate.open();close(s);}
    }

    @Test public void failedCsvAndSaveKeepPreviousCatalogGenerationAndFolderOwnership()throws Exception {
        Storage storage=new Storage(),otherStorage=new Storage();BridgeService s=service(storage,NO_SEND,16),other=service(otherStorage,NO_SEND,16);
        try {
            BridgeConfig old=config("old",false);s.configure(old,false,false);
            for(boolean saving:new boolean[]{false,true}) {
                BridgeConfig bad=config(saving?"bad-save":"bad-csv",false);
                if(saving)storage.failedSave=bad.endpoint;else storage.failedCatalog=bad.endpoint;
                failed(configure(s,bad,true));assertSame(old,s.config());assertEquals(1,s.snapshot().catalogSize);responsive(s,true);
                try{other.configure(old,false,false);fail("The previous configuration must still own the folder");}catch(IOException expected){assertTrue(expected.getMessage().contains("Another instance"));}
            }
            assertTrue(storage.saved.isEmpty());s.awaitIdle(3000);s.configure(disabled(),false,false);other.configure(old,false,false);
        } finally {close(s);close(other);}
    }

    @Test public void failedSaveCleansUpNewLockOutsideModelMonitor()throws Exception {
        Storage storage=new Storage();BridgeService s=service(storage,NO_SEND,16);Gate cleanup=storage.cleanup=new Gate();
        try {
            s.awaitReady(3000);BridgeConfig previous=s.config(),next=config("failure",false);storage.failedSave=next.endpoint;
            Future<?> update=configure(s,next,true);cleanup.reached();assertSame(previous,s.config());responsive(s,false);
            cleanup.open();failed(update);assertEquals(1,storage.releases.get());
            Storage secondStorage=new Storage();BridgeService second=service(secondStorage,NO_SEND,16);
            try{second.configure(config("second",false),false,false);}finally{close(second);}
        } finally {cleanup.open();close(s);}
    }

    @Test public void failedConfigurationsDoNotCancelPreviouslyQueuedDeliveries()throws Exception {
        Storage storage=new Storage();Gate network=new Gate();List<String> destinations=new CopyOnWriteArrayList<>();
        BridgeService s=service(storage,(u,j)->{destinations.add(u);network.pause();return new BridgeService.Response(200,"{}");},4);
        try {
            BridgeConfig old=config("old",true);s.configure(old,false,false);s.receive(drop());network.reached();s.receive(drop());
            BridgeConfig bad=config("bad",true);storage.failedCatalog=bad.endpoint;failed(configure(s,bad,true));
            storage.failedCatalog=null;storage.failedSave=bad.endpoint;failed(configure(s,bad,true));assertSame(old,s.config());
            network.open();s.awaitIdle(3000);assertEquals(Arrays.asList(old.endpoint,old.endpoint),destinations);
            assertEquals(2,s.snapshot().accepted);assertEquals(0,s.snapshot().skipped);
        } finally {network.open();close(s);}
    }

    @Test public void blockedAcquisitionAndCloseCannotPublishOrSaveObsoleteConfiguration()throws Exception {
        Storage storage=new Storage();BridgeService s=service(storage,NO_SEND,16);Gate acquire=storage.acquire=new Gate();
        try {
            s.awaitReady(3000);BridgeConfig previous=s.config();Future<?> update=configure(s,config("next",false),true);acquire.reached();responsive(s,false);
            edt(()->{s.close();return null;});assertTrue(s.snapshot().closed);assertFalse(s.snapshot().loading);
            acquire.open();failed(update);s.awaitClosed(3000);assertSame(previous,s.config());assertTrue(storage.saved.isEmpty());assertEquals(1,storage.releases.get());
        } finally {acquire.open();close(s);}
    }

    @Test public void closeDuringStartupCannotReviveTheService()throws Exception {
        Storage storage=new Storage();storage.initial=config("obsolete",false);Gate startup=storage.startup=new Gate();BridgeService s=service(storage,NO_SEND,16);
        try {
            startup.reached();BridgeConfig previous=s.config();edt(()->{s.close();return null;});s.awaitReady(1000);responsive(s,false);
            startup.open();s.awaitClosed(3000);assertSame(previous,s.config());assertEquals("Closed",s.snapshot().state);assertTrue(storage.catalogs.isEmpty());assertEquals(0,storage.acquisitions.get());
        } finally {startup.open();close(s);}
    }

    @Test public void disablePublishesBeforeSlowLockCleanupAndStillProtectsSecondInstance()throws Exception {
        Storage storage=new Storage(),otherStorage=new Storage();BridgeService s=service(storage,NO_SEND,16),other=service(otherStorage,NO_SEND,16);Gate cleanup=new Gate();
        try {
            BridgeConfig active=config("active",false);s.configure(active,false,false);storage.cleanup=cleanup;
            Future<?> disable=configure(s,disabled(),false);cleanup.reached();assertEquals("Disabled",s.snapshot().state);responsive(s,false);
            try{other.configure(active,false,false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Another instance"));}
            cleanup.open();disable.get(3,TimeUnit.SECONDS);other.configure(active,false,false);assertSame(active,other.config());
        } finally {cleanup.open();close(s);close(other);}
    }

    @Test public void cancelledAuditDoesNotHoldModelMonitorAndDeliveryQueueRemainsBounded()throws Exception {
        Storage storage=new Storage();Gate network=new Gate(),audit=storage.audit=new Gate();storage.auditStatus="Cancelled";AtomicInteger sends=new AtomicInteger();
        BridgeService s=service(storage,(u,j)->{sends.incrementAndGet();network.pause();return new BridgeService.Response(200,"{}");},2);
        try {
            s.configure(config("old",true),false,false);s.receive(drop());network.reached();s.receive(drop());
            s.configure(config("new",false),false,false);network.open();audit.reached();assertEquals("Cancelled",s.snapshot().reviews.get(1).status);
            responsive(s,true);s.receive(drop());s.receive(drop());assertEquals(2,s.snapshot().queued);assertEquals(1,s.snapshot().failed);
            assertEquals("Queue full",s.snapshot().reviews.get(4).status);assertEquals(1,sends.get());
            audit.open();close(s);assertTrue(storage.audited.stream().anyMatch(r->r.status.equals("Cancelled")));assertEquals(1,sends.get());
        } finally {network.open();audit.open();close(s);}
    }

    @Test public void disableAndCloseCancelQueuedDropsAndPingsButAllowAnInFlightRequestToFinish()throws Exception {
        for(boolean closing:new boolean[]{false,true}) {
            Storage storage=new Storage();Gate network=new Gate();AtomicInteger sends=new AtomicInteger();
            BridgeService s=service(storage,(u,j)->{sends.incrementAndGet();network.pause();return new BridgeService.Response(200,"{}");},8);
            try {
                BridgeConfig old=config("old",true);s.configure(old,false,true);network.reached();s.receive(drop());s.receive(drop());
                s.configure(old,false,true);s.receive(drop()); // Queue a confirmation and a drop in the current generation too.
                if(closing)edt(()->{s.close();return null;});else s.configure(disabled(),false,false);
                responsive(s,false);network.open();if(closing)s.awaitClosed(3000);else s.awaitIdle(3000);
                assertEquals(1,sends.get());assertEquals(3,s.snapshot().skipped);assertEquals(3,storage.audited.size());
                for(BridgeService.Review review:s.snapshot().reviews)assertEquals("Cancelled",review.status);
            } finally {network.open();close(s);}
        }
    }

    @Test public void saturatedConfigurationQueueRejectsAndCloseCancelsPendingWorkWithoutWaitingForSave()throws Exception {
        Storage storage=new Storage();BridgeService s=service(storage,NO_SEND,16);Gate saveGate=storage.save=new Gate();
        Storage otherStorage=new Storage();BridgeService other=service(otherStorage,NO_SEND,16);
        try {
            s.awaitReady(3000);BridgeConfig previous=s.config(),next=config("accepted",false);Future<?> running=configure(s,next,true);saveGate.reached();
            BlockingQueue<IOException> failures=new LinkedBlockingQueue<>();List<Future<?>> pending=new ArrayList<>();
            for(int i=0;i<11;i++)pending.add(callers.submit(()->{try{s.configure(next,true,true);fail("Queued config should be cancelled");}catch(IOException e){failures.add(e);}}));
            // The configuration lane permits eight waiting changes, with the ninth and later rejected visibly.
            for(int i=0;i<3;i++){IOException rejection=failures.poll(3,TimeUnit.SECONDS);assertNotNull(rejection);assertTrue(rejection.getMessage().contains("queue is full"));}
            assertTrue(s.snapshot().logs.stream().anyMatch(l->l.message.contains("settings queue is full")));responsive(s,false);
            edt(()->{s.close();return null;});for(Future<?> pendingChange:pending)pendingChange.get(3,TimeUnit.SECONDS);
            assertSame(previous,s.config());assertFalse(running.isDone());responsive(s,false);
            try{other.configure(next,false,false);fail("In-progress save must retain its folder lock until cleanup");}catch(IOException expected){assertTrue(expected.getMessage().contains("Another instance"));}
            saveGate.open();failed(running);s.awaitClosed(3000);assertSame(previous,s.config());assertEquals("Closed",s.snapshot().state);
            assertEquals(Collections.singletonList(next),storage.saved);assertEquals(1,storage.releases.get());other.configure(next,false,false);
        } finally {saveGate.open();close(s);close(other);}
    }

    @Test public void guiSaveRunsOffEdtAndRepeatedClicksDoNotQueueWorkersOrOverwriteLaterEdits()throws Exception {
        Storage storage=new Storage();storage.initial=config("initial",false);BridgeService s=service(storage,NO_SEND,16);Gate saveGate=new Gate();
        try {
            s.awaitReady(3000);BridgeReviewGUI panel=edt(()->new BridgeReviewGUI(s));storage.save=saveGate;CountDownLatch completed=new CountDownLatch(1);
            edt(()->{field(panel,"endpoint").setText("https://edited.invalid/ingest");save(panel).addPropertyChangeListener("enabled",e->{if(Boolean.TRUE.equals(e.getNewValue()))completed.countDown();});save(panel).doClick(0);return null;});
            saveGate.reached();responsive(s,true);
            edt(()->{assertFalse(save(panel).isEnabled());for(int i=0;i<20;i++)save(panel).doClick(0);field(panel,"guild").setText("999");panel.refresh();assertFalse(save(panel).isEnabled());return null;});
            saveGate.open();assertTrue(completed.await(3,TimeUnit.SECONDS));assertEquals(1,storage.saved.size());assertEquals("https://edited.invalid/ingest",s.config().endpoint);
            assertEquals(storage.initial.guildId,s.config().guildId);edt(()->{assertEquals("999",field(panel,"guild").getText());return null;});s.awaitIdle(3000);
        } finally {saveGate.open();close(s);}
    }
}
