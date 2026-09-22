package tomato.gui.modern;

import packets.packetcapture.logger.DiscoveryLog;
import javax.swing.*;

/** Current collector state is independent of the displayed data revision, including paused views. */
public final class CollectionControl extends JCheckBox {
    public static final String EFFECT = "Shared with Logging, Runs, Timeline, resource/buff history and recorded Inspect builds. "
        + "Capture connection must also be running. Does not stop the network sniffer or independent Chat, loot and DPS processing.";
    private final DiscoveryLog log;
    private final Timer timer;

    public CollectionControl(DiscoveryLog log, Runnable changed) {
        super("Gameplay & diagnostics collection");
        this.log = log;
        setToolTipText(EFFECT);
        getAccessibleContext().setAccessibleDescription(EFFECT);
        setVisible(!log.isHistorical());
        setEnabled(!log.isHistorical());
        refresh();
        addActionListener(e -> { log.setEnabled(isSelected()); changed.run(); });
        timer = new Timer(250, e -> refresh());
    }

    public void refresh() { setSelected(log.isEnabled()); }
    @Override public void addNotify() { super.addNotify(); refresh(); timer.start(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }

    public static String status(DiscoveryLog log, boolean paused) {
        if (log.isHistorical()) return "Saved history" + (paused ? " · View paused" : "");
        return "Gameplay & diagnostics collection: " + (log.isEnabled() ? "on" : "off")
            + (paused ? " · View paused (collection state is current)" : "");
    }
}
