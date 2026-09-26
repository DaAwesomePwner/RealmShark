package tomato;

import assets.AssetExtractor;
import java.io.File;
import javax.swing.*;
import packets.PacketType;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.register.Register;
import packets.packetcapture.sniff.assembly.TcpStreamErrorHandler;
import realmshark.branding.AppIdentity;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.TomatoRootController;
import tomato.backend.data.AbilityScalingManager;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.maingui.TomatoBandwidth;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.warnings.JavaOutOfMemoryGUI;
import tomato.realmshark.CrashLogger;
import tomato.version.Version;
import util.PropertiesManager;
import util.Util;

/**
 * Tomato is an EXAMPLE MOD built on top of RealmShark, an API used to
 * unwrapped Realm of the Mad Gods packets. The Packets are grabbed
 * directly from the network tap using a sniffer. It is not possible
 * to modify, block or create packets to be sent, similar to WireShark.
 * <p>
 * The register should be used to sign up for packets. If said packet is
 * received then the lambda function passed in as the second argument can
 * be used to trigger any functions listening to registered packets.
 */
public class Tomato {

    private static volatile PacketProcessor packetProcessor;
    // Capture lifecycle decisions belong to the EDT; retain the old worker until its callback.
    private static boolean stoppingCapture, restartCapture;
    private static TomatoRootController rootController;
    private static CapturePublication capturePublication;
    private static boolean preview;
    private static boolean assetsReady, setupBusy;

    public static boolean isPreview() { return preview; }
    public static boolean isCaptureRunning() { return packetProcessor != null; }

    /** Explicit user choice, distinct from errors, transport cleanup and setup retries. */
    public static void setCaptureRequested(boolean requested) { setCaptureRequested(requested, PacketProcessor::new); }

    static void setCaptureRequested(boolean requested, java.util.function.Supplier<PacketProcessor> factory) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> setCaptureRequested(requested, factory)); return; }
        if (preview) return;
        PropertiesManager.setProperties("sniffer", requested ? "T" : "F");
        if (requested) startPacketSniffer(factory); else stopPacketSniffer();
    }

    /** Only the initial readiness result can honor the saved auto-start choice. */
    static void finishAssetSetup(boolean initial, boolean ready, java.util.function.Supplier<PacketProcessor> factory) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Setup completion belongs to the EDT");
        assetsReady = ready; setupBusy = false;
        TomatoMenuBar.setCaptureControls(false, ready);
        if (initial && ready && "T".equals(PropertiesManager.getProperty("sniffer"))) startPacketSniffer(factory);
    }

    public static void main(String[] args) {
        AppIdentity.initialize();
        // Load before any GUI/model presets are read; readers themselves never perform I/O.
        PropertiesManager.preload();
        for (String arg : args) {
            if ("--preview".equals(arg)) preview = true;
        }
        if (preview) {
            tomato.history.AppHistory.start(true);
            SwingUtilities.invokeLater(() -> {
                tomato.gui.modern.VioletTheme.install();
                new TomatoGUI(new TomatoData()).create();
            });
            return;
        }
        System.out.println(
            "Java Version: " +
                System.getProperty("java.version") +
                " : (" +
                System.getProperty("sun.arch.data.model") +
                " - bit)"
        );
        parseArgs(args);
        tomato.history.AppHistory.start(false);

        Util.setSaveLogs(false); // turns the logger to, save in to files.
        TcpStreamErrorHandler.INSTANCE.setErrorMessageHandler(
            Tomato::errorMessageHandler
        );
        TcpStreamErrorHandler.INSTANCE.setErrorStopHandler(
            TomatoMenuBar::stopPacketSniffer
        );

        load();
    }

    private static void parseArgs(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                usage();
                AppIdentity.exit(0);
            }
        }

        parseCustomAssetPath(args);
    }

    private static void usage() {
        System.out.println("Usage: java -jar " + AppIdentity.NAME + "-" + AppIdentity.version() + ".jar [options]");
        System.out.println("Options:");
        System.out.println("  --help, -h          Show this help message");
        System.out.println("  --preview          Open the UI without capture, API calls or asset extraction");
        System.out.println(
            "  --path <file_path>  Specify custom resources.assets file path"
        );
    }

    /**
     * Initializes crucible data by fetching from API on startup
     * This provides pre-launch crucible bonuses without waiting for game packets
     */
    private static void initializeCrucibleData() {
        long startTime = System.currentTimeMillis();
        tomato.backend.data.CrucibleBonusManager.fetchCrucibleDataFromApi();
        long endTime = System.currentTimeMillis();

        boolean apiDataLoaded =
            tomato.backend.data.CrucibleBonusManager.isApiDataLoaded();
        if (apiDataLoaded) {
            System.out.println(
                "[Crucible] API data loaded (" + (endTime - startTime) + "ms)"
            );
        } else {
            System.out.println(
                "[Crucible] Using packet data (" + (endTime - startTime) + "ms)"
            );
        }
    }

    private static void parseCustomAssetPath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--path") && i + 1 < args.length) {
                String customPath = args[i + 1];
                File customFile = new File(customPath);
                if (customFile.exists() && customFile.isFile()) {
                    AssetExtractor.setRealmResPath(customPath);
                    System.out.println(
                        "Using custom resources.assets path: " + customPath
                    );
                } else {
                    System.err.println("Invalid path provided: " + customPath);
                    System.err.println("Falling back to default paths.");
                }
                break;
            }
        }
    }

    /**
     * Main boot up method to create data storage, start controllers and attach
     * the data to the controllers and link the data to the view to be displayed.
     */
    public static void load() {
        try {
            CrashLogger.loadThisClass();
            TomatoGUI.loadThemePreset();
            TomatoData data = new TomatoData();
            loadControllers(data);
            initializeAndOpen(data, () -> {
                new TomatoGUI(data).create();
                data.publishDungeonStats();
                beginAssetSetup(null, false);
            });
            CheckVersion.checkVersion();
            Thread crucible = new Thread(Tomato::initializeCrucibleData, "crucible-startup");
            crucible.setDaemon(true);
            crucible.start();
        } catch (OutOfMemoryError | StackOverflowError e) {
            JavaOutOfMemoryGUI.crashDialog();
            AppIdentity.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            CrashLogger.printCrash(e);
        } catch (Throwable e) {
            e.printStackTrace();
            CrashLogger.printCrash(new RuntimeException(e));
        } finally {
            dispose();
        }
    }

    /**
     * Loads controllers and adds them to the root controller list.
     *
     * @param data Main root controller.
     */
    private static void loadControllers(TomatoData data) {
        rootController = new TomatoRootController(data);
        capturePublication = new CapturePublication(data);
        // Create realm packet capture instance and add to root controller
        TomatoPacketCapture packCap = new TomatoPacketCapture(data);
        packetRegister(packCap);
        rootController.addController(packCap);

        // Initialize ability scaling manager
        AbilityScalingManager.getInstance().initialize();
    }

    /**
     * Disposes all controllers
     */
    public static void dispose() {
        if (rootController != null) rootController.dispose();
        //        if (packetProcessor != null) packetProcessor.stopSniffer();
    }

    /**
     * Error message handler from the TCP stream constructor.
     *
     * @param errorMsg Display message string
     * @param dump     Log dump string
     */
    private static void errorMessageHandler(String errorMsg, String dump) {
        ChatGUI.appendTextAreaChat(errorMsg);
        Util.printLogs(dump);
    }

    /**
     * Packet register for listening to incoming or outgoing packets from realm client.
     *
     * @param packCap Packet capture controller.
     */
    private static void packetRegister(TomatoPacketCapture packCap) {
        Register.INSTANCE.subscribePacketLogger(TomatoBandwidth::setInfo);

        Register.INSTANCE.register(
            PacketType.CREATE_SUCCESS,
            packCap::packetCapture
        );
        Register.INSTANCE.register(PacketType.ENEMYHIT, packCap::packetCapture);
        Register.INSTANCE.register(
            PacketType.PLAYERSHOOT,
            packCap::packetCapture
        );
        Register.INSTANCE.register(PacketType.DAMAGE, packCap::packetCapture);
        Register.INSTANCE.register(
            PacketType.PLAYERHIT,
            packCap::packetCapture
        );
        Register.INSTANCE.register(
            PacketType.ENEMYSHOOT,
            packCap::packetCapture
        );
        Register.INSTANCE.register(
            PacketType.GROUNDDAMAGE,
            packCap::packetCapture
        );
        Register.INSTANCE.register(PacketType.AOE, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.MOVE, packCap::packetCapture);
        Register.INSTANCE.register(
            PacketType.SERVERPLAYERSHOOT,
            packCap::packetCapture
        );
        Register.INSTANCE.register(PacketType.ALLYSHOOT, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.UPDATE, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.NEWTICK, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.MAPINFO, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.STASIS, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.TEXT, packCap::packetCapture);
        Register.INSTANCE.register(PacketType.ACCOUNTLIST, packCap::packetCapture);
        Register.INSTANCE.register(
            PacketType.NOTIFICATION,
            packCap::packetCapture
        );
        Register.INSTANCE.register(
            PacketType.EXALTATION_BONUS_CHANGED,
            packCap::packetCapture
        );
        Register.INSTANCE.register(
            PacketType.VAULT_UPDATE,
            packCap::packetCapture
        );
        Register.INSTANCE.register(
            PacketType.QUEST_FETCH_RESPONSE,
            packCap::packetCapture
        );
        Register.INSTANCE.register(PacketType.HELLO, packCap::packetCapture);
        Register.INSTANCE.register(
            PacketType.TRADEREQUESTED,
            packCap::packetCapture
        );

        // Register CrucibleResponsePacket if it exists
        try {
            Class<?> cruciblePacketClass = Class.forName(
                "packets.incoming.CrucibleResponsePacket"
            );
            Register.INSTANCE.register(
                PacketType.CRUCIBLE_RESPONSE,
                packCap::packetCapture
            );
        } catch (ClassNotFoundException e) {
            // CrucibleResponsePacket not available in this version
            System.out.println("CrucibleResponsePacket not available");
        }
    }

    /**
     * Start the packet sniffer.
     */
    public static void startPacketSniffer() {
        startPacketSniffer(PacketProcessor::new);
    }

    /** The production lifecycle with a replaceable worker factory for adapter-free integration checks. */
    static void startPacketSniffer(java.util.function.Supplier<PacketProcessor> factory) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> startPacketSniffer(factory));
            return;
        }
        if (stoppingCapture) { restartCapture = true; return; }
        if (preview || setupBusy || !assetsReady) return;
        if (packetProcessor == null) {
            if (capturePublication == null) throw new IllegalStateException("Capture model has not been initialized");
            CapturePublication publication = capturePublication;
            ChatGUI.resetObservedIgnores();
            PacketProcessor next = factory.get();
            packetProcessor = next;
            next.setBoundaryListener(() -> publication.boundary(next));
            TomatoMenuBar.setCaptureControls(true, true);
            TomatoGUI.setStateOfSniffer(true);
            next.setReadinessListener(state -> SwingUtilities.invokeLater(() -> {
                if (packetProcessor == next && !stoppingCapture) TomatoGUI.setCaptureReadiness(state);
            }));
            next.setCaptureStatusListener(message -> SwingUtilities.invokeLater(() -> {
                if (packetProcessor == next && !stoppingCapture) TomatoGUI.setCaptureDetail(message);
            }));
            next.setStoppedListener(() -> {
                publication.terminated(next);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (packetProcessor == next) {
                        boolean requested = stoppingCapture;
                        packetProcessor = null;
                        stoppingCapture = false;
                        TomatoMenuBar.setCaptureControls(false, assetsReady && !setupBusy);
                        TomatoGUI.setStateOfSniffer(false);
                        if (restartCapture) {
                            restartCapture = false;
                            startPacketSniffer(factory);
                        } else if (!requested) {
                            TomatoGUI.setCaptureFailure(next.getStopReason());
                            TomatoGUI.setCaptureReadiness(next.getCaptureState());
                        }
                    }
                });
            });
            publication.started(next);
            try { next.start(); }
            catch (RuntimeException | Error failure) {
                publication.terminated(next);
                packetProcessor = null;
                TomatoMenuBar.setCaptureControls(false, assetsReady && !setupBusy);
                TomatoGUI.setStateOfSniffer(false);
                throw failure;
            }
        }
    }

    /**
     * Stop the packet sniffer.
     */
    public static void stopPacketSniffer() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(Tomato::stopPacketSniffer);
            return;
        }
        restartCapture = false;
        if (packetProcessor != null && !stoppingCapture) {
            stoppingCapture = true;
            PacketProcessor stopping = packetProcessor;
            stopping.requestStop();
            capturePublication.stopRequested(stopping);
            TomatoMenuBar.setCaptureControls(false, false);
            TomatoGUI.setCaptureReadiness(packets.packetcapture.CaptureState.STOPPING);
            TomatoGUI.setCaptureDetail("Stopping capture connection…");
            Thread stop = new Thread(stopping::stopSniffer, "capture-stop");
            stop.setDaemon(true);
            stop.start();
        }
    }

    /**
     * Load all presets
     */
    private static void bootload(TomatoData data) {
        try {
            data.bootload();
        } catch (RuntimeException failure) {
            String message = "Dungeon history unavailable; dungeon.stats preserved. Dungeon saves are suspended until a successful reload or restart after repairing the file.";
            System.err.println(message);
            try { packets.packetcapture.CaptureDiagnostics.record(message, failure); }
            catch (RuntimeException loggingFailure) { System.err.println("Unable to record dungeon history load failure."); }
        }
        data.loadPropList("chatPingMessages");
        data.loadPropList("entityIdPings");
        data.loadPropList("itemPings");
    }

    /** Complete local initialization before opening the shell and checking capture readiness. */
    static void initializeAndOpen(TomatoData data, Runnable openWindow)
        throws InterruptedException, java.lang.reflect.InvocationTargetException {
        bootload(data);
        SwingUtilities.invokeAndWait(openWindow);
    }

    public static void chooseAssets() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(Tomato::chooseAssets); return; }
        if (preview || setupBusy || isCaptureRunning()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose resources.assets");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Game resources.assets", "assets"));
        if (chooser.showOpenDialog(TomatoGUI.getFrame()) == JFileChooser.APPROVE_OPTION)
            beginAssetSetup(chooser.getSelectedFile(), true);
    }

    public static void retryAssets() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(Tomato::retryAssets); return; }
        beginAssetSetup(null, true);
    }

    private static void beginAssetSetup(File chosen, boolean recover) {
        beginAssetSetup(chosen, recover, PacketProcessor::new);
    }

    static SwingWorker<Boolean, String> beginAssetSetup(File chosen, boolean recover, java.util.function.Supplier<PacketProcessor> factory) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Setup belongs to the EDT");
        if (preview || setupBusy || isCaptureRunning()) return null;
        boolean readyBeforeAttempt = assetsReady;
        setupBusy = true;
        TomatoMenuBar.setCaptureControls(false, false);
        TomatoGUI.setSetupState(recover ? "Preparing assets… Saved history remains available." : "Checking local assets… Saved history remains available.", false, true);
        SwingWorker<Boolean, String> worker = new SwingWorker<Boolean, String>() {
            private boolean sourceAvailable;
            private boolean activeCacheReady = readyBeforeAttempt;
            @Override protected Boolean doInBackground() throws Exception {
                if (!AssetExtractor.hasUsableCache()) activeCacheReady = false;
                sourceAvailable = chosen != null || AssetExtractor.assetFile() != null;
                if (chosen != null) AssetExtractor.recover(chosen, Version.ASSET_CACHE_VERSION, this::publish);
                else {
                    boolean needed = AssetExtractor.needsExtraction(Version.ASSET_CACHE_VERSION);
                    if (recover && needed) AssetExtractor.recover(AssetExtractor.assetFile(), Version.ASSET_CACHE_VERSION, this::publish);
                    else if (needed) return false;
                    else try { AssetExtractor.reloadAssetsOnRunningApp(); }
                    catch (java.io.IOException invalidActiveCache) {
                        activeCacheReady = false;
                        File source = AssetExtractor.assetFile();
                        if (!recover || source == null) throw invalidActiveCache;
                        AssetExtractor.recover(source, Version.ASSET_CACHE_VERSION, this::publish);
                    }
                }
                return true;
            }
            @Override protected void process(java.util.List<String> messages) {
                if (!messages.isEmpty()) TomatoGUI.setSetupState(messages.get(messages.size() - 1) + " · Saved history remains available.", false, true);
            }
            @Override protected void done() {
                setupBusy = false;
                String message;
                boolean completed = false;
                try {
                    assetsReady = get();
                    completed = true;
                    message = assetsReady ? (sourceAvailable ? "Assets ready · Enter a fresh area or reconnect the game after capture starts."
                        : "Cached assets loaded · Source file unavailable; freshness unverified. Choose assets to refresh, or start capture with the cached definitions.")
                        : "Assets missing or outdated · Choose resources.assets, or Retry assets if the game is installed. Browse saved history without capture.";
                    if (assetsReady) TomatoGUI.assetsReloaded();
                } catch (Exception failure) {
                    assetsReady = activeCacheReady;
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    message = "Asset setup failed (" + cause.getClass().getSimpleName() + "). "
                        + (assetsReady ? "Active cached assets remain ready for manual capture. Retry assets to recheck them, or choose another resources.assets file."
                            : "Choose a readable resources.assets file and a writable app folder, then Retry assets. Saved history remains available.");
                }
                TomatoGUI.setSetupState(message, assetsReady, false);
                finishAssetSetup(completed && !recover && chosen == null, assetsReady, factory);
            }
        };
        worker.execute();
        return worker;
    }
}
