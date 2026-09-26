package tomato.gui.stats;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.swing.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.SmartScroller;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.*;
import tomato.realmshark.enums.CharacterStatistics;
import tomato.realmshark.enums.CharacterClass;
import tomato.realmshark.enums.LootBags;

public class LootGUI extends JPanel {

    private static volatile LootGUI INSTANCE;
    private final LootDashboard dashboard = new LootDashboard();
    private final Deque<LootEntry> pending = new ArrayDeque<>();
    private boolean renderQueued, clearRequested;
    static final int LOG_LIMIT = LootDashboard.RECENT_LIMIT;
    private static final int RENDER_BATCH = 64;

    public LootDashboard getDashboard() { return dashboard; }

    private static TomatoData data;

    private boolean cleared;

    private volatile boolean update;

    private final JPanel lootPanel;

    private static Font mainFont;

    private int lootDrops;
    private final SendLoot.Session sharing;
    private final JTextArea sharingStatus = new JTextArea(2, 0);
    private final JTextArea sharingDetails = new JTextArea(10, 48);
    private final Timer sharingRefresh;
    public static boolean filterWhiteBag = false;
    public static boolean filterOrangeBag = false;
    public static boolean filterRedBag = false;
    public static boolean filterGoldBag = false;
    public static boolean filterEggBag = false;
    public static boolean filterBlueBag = false;
    public static boolean filterTealBag = false;
    public static boolean filterPurpleBag = false;
    public static boolean filterPinkBag = false;
    public static boolean filterBrownBag = false;

    public LootGUI(TomatoData data) {
        this(data, SendLoot.session());
    }

    LootGUI(TomatoData data, SendLoot.Session sharing) {
        this.sharing = sharing;
        LootGUI.data = data;
        lootDrops = 0;
        INSTANCE = this;
        setLayout(new BorderLayout());

        lootPanel = new JPanel();

        lootPanel.setLayout(new BoxLayout(lootPanel, BoxLayout.Y_AXIS));

        lootPanel.add(new JLabel("Change instance to see loot info."));
        validate();

        JScrollPane scroll = new JScrollPane(lootPanel);
        scroll.setVerticalScrollBarPolicy(
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
        );
        new SmartScroller(scroll, 0);
        JTabbedPane views = new JTabbedPane();
        views.addTab("Explore loot", new LootDashboard(dashboard));
        JPanel log = new JPanel(new BorderLayout(0, 8));
        log.add(StatsUi.heading("Live loot log", "Original drop details, enchants and sharing information. Bag visibility follows Filter Loot in the menu."), BorderLayout.NORTH);
        log.add(scroll, BorderLayout.CENTER);
        views.addTab("Live log", log);
        add(views, BorderLayout.CENTER);
        sharingStatus.setEditable(false);
        sharingStatus.setLineWrap(true);
        sharingStatus.setWrapStyleWord(true);
        sharingStatus.setFont(ContentStyle.metadata(ContentStyle.body()));
        sharingStatus.getAccessibleContext().setAccessibleName("Legacy loot delivery status");
        sharingDetails.setEditable(false);
        sharingDetails.setLineWrap(true);
        sharingDetails.setWrapStyleWord(true);
        sharingDetails.setFont(sharingStatus.getFont());
        sharingDetails.getAccessibleContext().setAccessibleName("Legacy loot delivery details");
        JScrollPane deliveryStatus = new JScrollPane(sharingStatus) {
            @Override public Dimension getPreferredSize() {
                // A status update/error must not consume the loot table's compact-height budget.
                Insets insets = getInsets();
                return new Dimension(0, 2 * sharingStatus.getFontMetrics(sharingStatus.getFont()).getHeight()
                    + insets.top + insets.bottom + 2);
            }
        };
        deliveryStatus.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        deliveryStatus.setBorder(BorderFactory.createEmptyBorder());
        JButton details = new JButton("Details…");
        details.setToolTipText("Delivery counters, last error, and cancellation/confirmation semantics");
        details.addActionListener(e -> JOptionPane.showMessageDialog(this, new JScrollPane(sharingDetails),
            "Legacy loot delivery", JOptionPane.INFORMATION_MESSAGE));
        JPanel deliveryBar = new JPanel(new BorderLayout(8, 0));
        deliveryBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        deliveryBar.add(deliveryStatus, BorderLayout.CENTER);
        deliveryBar.add(details, BorderLayout.EAST);
        add(deliveryBar, BorderLayout.NORTH);
        refreshDeliveryStatus();
        sharingRefresh = new Timer(500, e -> refreshDeliveryStatus());
        sharingRefresh.setCoalesce(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) { refreshDeliveryStatus(); sharingRefresh.start(); }
                else sharingRefresh.stop();
            }
        });
    }

    /** Poll once per visible refresh, never enqueue a Swing event per delivery/counter change. */
    void refreshDeliveryStatus() {
        LootDelivery.Status status = sharing.snapshot();
        String text = "Legacy loot sharing: " + status.state
            + "\nQueued total: " + status.queued + " · Waiting: " + status.waiting
            + " · Pending merge: " + sharing.pendingBags() + " · Active: " + status.active
            + "\nSent to socket: " + status.sentToSocket + " (unconfirmed) · Dropped before send: " + status.dropped
            + " · Uncertain: " + status.uncertain
            + "\nLast error: " + (status.lastError.isEmpty() ? "None" : status.lastError)
            + "\nSocket writes are not application confirmation. Merged bags count as one payload. Opt-out cancels unsent bags; an in-flight send cannot be retracted and is counted as uncertain. Uncertain sends are not retried.";
        if (!text.equals(sharingDetails.getText())) sharingDetails.setText(text);
        String mode = status.closed ? "Stopped" : status.preview ? "Preview" : status.enabled ? "On" : "Opted out";
        String summary = "Legacy loot: " + mode + " · Queued: " + status.queued
            + " · Socket: " + status.sentToSocket + " (unconfirmed)"
            + "\nDropped: " + status.dropped + " · Uncertain: " + status.uncertain
            + " · Error: " + (status.lastError.isEmpty() ? "None" : "See Details");
        if (!summary.equals(sharingStatus.getText())) {
            sharingStatus.setText(summary);
            sharingStatus.setCaretPosition(0);
        }
        sharingStatus.getAccessibleContext().setAccessibleDescription(text);
    }

    @Override public void removeNotify() {
        sharingRefresh.stop();
        super.removeNotify();
    }

    public static void update(
        MapInfoPacket map,
        Entity bag,
        Entity dropper,
        Entity player,
        long time
    ) {
        update(map,bag,dropper,player,time,DropContext.capture(map,player,time,null));
    }

    public static void update(MapInfoPacket map,Entity bag,Entity dropper,Entity player,long time,DropContext context){
        INSTANCE.updateGui(map,bag,dropper,player,time,context);
    }

    public static void updateExaltStats() {
        LootGUI view = INSTANCE;
        if (view == null) return;
        synchronized (view.pending) {
            view.update = true;
            if (!view.cleared) {
                view.cleared = true;
                view.clearRequested = true;
                view.queueRender();
            }
        }
    }

    private void updateGui(
        MapInfoPacket map,
        Entity bag,
        Entity dropper,
        Entity player,
        long time, DropContext context
    ) {
        if (player == null || !update) return;

        dashboard.receive(map, bag, dropper, time,context);
        LootEntry entry = new LootEntry(map, bag, dropper, player, time);
        synchronized (pending) {
            entry.number = ++lootDrops;
            pending.addLast(entry);
            if (pending.size() > LOG_LIMIT) pending.removeFirst();
            queueRender();
        }

        // play() applies the enable/mute/volume gates itself, so a matching bag whose sound is
        // turned off is still recorded as a "Matched · alert off" decision; nothing more plays.
        if (isWhiteBag(bag)) Sound.whitebag.play();
        if (isOrangeBag(bag)) Sound.orangebag.play();
        if (isRedBag(bag)) Sound.redbag.play();
        if (isGoldBag(bag)) Sound.goldbag.play();
        if (isEggBag(bag)) Sound.eggbag.play();
        if (isBlueBag(bag)) Sound.bluebag.play();
        notifyItems(bag, Sound.custom::play,
            () -> sharing.sendLoot(data, map, bag, dropper, player, time));

    }

    /** Called with pending locked. Capture never constructs Swing components or waits for Swing. */
    private void queueRender() {
        if (renderQueued) return;
        renderQueued = true;
        SwingUtilities.invokeLater(this::drainRows);
    }

    private void drainRows() {
        List<LootEntry> batch = new ArrayList<>();
        boolean clear;
        synchronized (pending) {
            clear = clearRequested; clearRequested = false;
            for (int i = 0; i < RENDER_BATCH && !pending.isEmpty(); i++) batch.add(pending.removeFirst());
        }
        if (clear) lootPanel.removeAll();
        for (LootEntry entry : batch) {
            JPanel panel = createMainBox(entry);
            panel.setVisible(isBagVisible(entry.bag));
            lootPanel.add(panel, 0);
            if (lootPanel.getComponentCount() > LOG_LIMIT) lootPanel.remove(lootPanel.getComponentCount() - 1);
        }
        safeRefreshPanel();
        synchronized (pending) {
            renderQueued = false;
            if (!pending.isEmpty() || clearRequested) queueRender();
        }
    }

    /** Local alerts precede optional sharing. Callbacks allow verification without audio or networking. */
    void notifyItems(Entity bag, java.util.function.LongConsumer alert, Runnable share) {
        StatData unique = bag.stat.get(StatType.UNIQUE_DATA_STRING);
        String[] enchants = unique == null || unique.stringStatValue == null
            ? new String[0] : unique.stringStatValue.split(",", -1);
        for (int slot = 0; slot < 8; slot++) {
            StatData item = bag.stat.get(StatType.INVENTORY_0_STAT.get() + slot);
            if (item == null || item.statValue < 1) continue;
            String name = IdToAsset.objectName(item.statValue);
            String enchantText = notificationEnchants(slot < enchants.length ? enchants[slot] : null);
            boolean enchantMatch = !enchantText.isEmpty() && data.isEnchantPing(enchantText);
            // Records the item decision (match or no match); several matching rules still describe one dropped item.
            long decision = tomato.realmshark.AlertDecisions.lootItem(data.getItemPings(), item.statValue, name, enchantText, enchantMatch);
            if (decision != 0) alert.accept(decision);
        }
        if (sharing.isEnabled()) share.run();
    }

    private static String notificationEnchants(String encoded) {
        if (ParseEnchants.summarize(encoded).applied <= 0) return "";
        try {
            // Valid URL Base64 may omit padding; the legacy ID decoder requires complete groups.
            while (encoded.length() % 4 != 0) encoded += "=";
            StringBuilder text = new StringBuilder();
            // Unlike the legacy display parser, include applied enchants after empty/locked slots.
            for (short id : ParseEnchants.extractEnchantIds(encoded)) {
                text.append(ParseEnchants.ENCHANTS.getOrDefault(id, "Unknown"))
                    .append('(').append(id).append(")\n");
            }
            return text.toString();
        } catch (RuntimeException e) {
            // One malformed slot must not suppress other item alerts or ordinary bag processing.
            return "";
        }
    }

    private boolean isBagVisible(Entity bag) {
        if (isWhiteBag(bag) && !filterWhiteBag) return false;
        if (isOrangeBag(bag) && !filterOrangeBag) return false;
        if (isRedBag(bag) && !filterRedBag) return false;
        if (isGoldBag(bag) && !filterGoldBag) return false;
        if (isEggBag(bag) && !filterEggBag) return false;
        if (isBlueBag(bag) && !filterBlueBag) return false;
        if (isTealBag(bag) && !filterTealBag) return false;
        if (isPurpleBag(bag) && !filterPurpleBag) return false;
        if (isPinkBag(bag) && !filterPinkBag) return false;
        if (isBrownBag(bag) && !filterBrownBag) return false;
        return true; // Show if no filter prevents it
    }

    public static void applyFilters() {
        LootGUI view = INSTANCE;
        if (view != null) onEdt(view::applyBagFilters);
    }

    private void applyBagFilters() {
        Component[] components = lootPanel.getComponents();
        for (Component component : components) {
            if (component instanceof JPanel) {
                JPanel lootEntry = (JPanel) component;
                Entity bag = (Entity) lootEntry.getClientProperty("bagEntity");
                if (bag != null) {
                    lootEntry.setVisible(isBagVisible(bag));
                }
            }
        }
        lootPanel.revalidate();
        lootPanel.repaint();
    }

    private boolean isBrownBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.BROWN.getId() || id == LootBags.BOOSTED_BROWN.getId()
        );
    }

    private boolean isPinkBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.PINK.getId() || id == LootBags.BOOSTED_PINK.getId()
        );
    }

    private boolean isPurpleBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.PURPLE.getId() ||
            id == LootBags.BOOSTED_PURPLE.getId()
        );
    }

    private boolean isTealBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.TEAL.getId() || id == LootBags.BOOSTED_TEAL.getId()
        );
    }

    private boolean isBlueBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.BLUE.getId() || id == LootBags.BOOSTED_BLUE.getId()
        );
    }

    private boolean isWhiteBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.WHITE.getId() || id == LootBags.BOOSTED_WHITE.getId()
        );
    }

    private boolean isOrangeBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.ORANGE.getId() ||
            id == LootBags.BOOSTED_ORANGE.getId()
        );
    }

    private boolean isRedBag(Entity bag) {
        int id = bag.objectType;
        return id == LootBags.RED.getId() || id == LootBags.BOOSTED_RED.getId();
    }

    private boolean isGoldBag(Entity bag) {
        int id = bag.objectType;
        return (
            id == LootBags.GOLD.getId() || id == LootBags.BOOSTED_GOLD.getId()
        );
    }

    private boolean isEggBag(Entity bag) {
        int id = bag.objectType;
        return id == LootBags.EGG.getId() || id == LootBags.BOOSTED_EGG.getId();
    }

    private static JPanel createMainBox(LootEntry entry) {
        JPanel mainPanel = new JPanel() {
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
            @Override public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, ContentStyle.color("border")),
                    BorderFactory.createEmptyBorder(0, 8, 0, 8)));
            }
        };
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.X_AXIS));

        mainPanel.putClientProperty("bagEntity", entry.bag);
        mainPanel.putClientProperty("dropNumber", entry.number);
        mainPanel.add(Box.createHorizontalGlue());

        displayText(mainPanel, "#" + entry.number);
        mainPanel.add(Box.createHorizontalStrut(8));
        displayText(mainPanel, entry.timestamp);
        mainPanel.add(Box.createHorizontalStrut(8));
        displayBagDungMob(entry, mainPanel);
        mainPanel.add(Box.createHorizontalStrut(12));
        displayBagLootIcons(entry.bag, mainPanel);

        mainPanel.add(Box.createHorizontalGlue());

        return mainPanel;
    }

    private static void displayBagDungMob(
        LootEntry entry,
        JPanel mainPanel
    ) {
        JPanel panel = new JPanel();

        panel.setPreferredSize(new Dimension(100, 24));
        panel.setMaximumSize(new Dimension(100, 24));
        panel.setLayout(new GridLayout(1, 4));

        displayBagIcon(entry.bag, entry.lootTime, entry.timestamp, panel);
        displayPlayerIcon(entry.map, entry.player, entry.exaltBonus, panel);
        displayDungeonIcon(entry.map, panel, entry.flames);
        displayMobIcon(entry.mob, entry.sharedLoot, panel);

        mainPanel.add(panel);
    }

    private static void displayText(JPanel mainPanel, String text) {
        JLabel label = new JLabel(text);
        label.setFont(getMainFont());
        label.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        mainPanel.add(label);
    }

    private static void displayPlayerIcon(
        MapInfoPacket map,
        Entity player,
        int exaltBonus,
        JPanel panel
    ) {
        int picon = 100;
        boolean isSeasonal = false;
        String name = "Unknown";
        if (map != null) {
            StatData sd = player.stat.get(StatType.SKIN_ID.get());
            StatData sesn = player.stat.get(StatType.SEASONAL.get());
            if (sd != null) {
                picon = sd.statValue;
                if (picon == 0) picon = player.objectType;
                name = IdToAsset.objectName(picon);
            }
            if (sesn != null) {
                if (sesn.statValue == 1) {
                    isSeasonal = true;
                }
            }
        }
        JLabel icon = new JLabel(ImageBuffer.getOutlinedIcon(picon, 20));
        if (exaltBonus != -1) {
            name += "<br>Exalt Bonus: " + exaltBonus + "%";
        }
        if (isSeasonal) {
            name += "<br>Seasonal";
        }
        icon.setToolTipText("<html>" + name + "</html>");

        panel.add(icon);
    }

    private static void displayBagLootIcons(
        Entity entity,
        JPanel mainPanel
    ) {
        JPanel panel = new JPanel();

        // A 20px item plus its enchant glow occupies 26px.
        panel.setPreferredSize(new Dimension(224, 28));
        panel.setMaximumSize(new Dimension(224, 28));
        panel.setLayout(new GridLayout(1, 8));

        String[] enchants = null;
        StatData udata = entity.stat.get(StatType.UNIQUE_DATA_STRING);
        if (udata != null && udata.stringStatValue != null) {
            enchants = udata.stringStatValue.split(",", -1);
        }

        for (int i = 0; i < 8; i++) {
            StatData sd = entity.stat.get(StatType.INVENTORY_0_STAT.get() + i);
            if (sd == null || sd.statValue < 1) {
                JPanel comp = new JPanel();
                comp.setMinimumSize(new Dimension(24, 24));
                panel.add(comp);
                continue;
            }
            int statValue = sd.statValue;
            String itemName = IdToAsset.objectName(statValue);
            if (itemName == null) itemName = "Item #" + statValue;
            String enchantText = "";
            String encoded = enchants != null && i < enchants.length ? enchants[i] : null;
            ParseEnchants.Summary summary = ParseEnchants.summarize(encoded);
            if (summary.slots < 0) itemName += "<br>Unknown enchant data";
            else {
                itemName += "<br>" + (summary.slots == 0 ? "Common / Unenchanted" : summary.rarity())
                    + " · " + summary.slots + " unlocked slots · " + summary.applied + " applied enchants";
                if (summary.slots > 0) {
                    try { enchantText = ParseEnchants.parse(encoded); }
                    catch (RuntimeException e) { enchantText = "Enchant descriptions unavailable"; }
                }
            }

            JLabel icon;
            if (summary.slots <= 0) {
                icon = new JLabel(ImageBuffer.getOutlinedIcon(statValue, 20));
            } else {
                Color glowColor;
                switch (summary.slots) {
                    case 1:
                        glowColor = new Color(0, 255, 0); // Green
                        break;
                    case 2:
                        glowColor = new Color(0, 200, 255); // Blue
                        break;
                    case 3:
                        glowColor = new Color(200, 0, 255); // Purple
                        break;
                    case 4:
                        glowColor = new Color(255, 215, 0); // Gold
                        break;
                    default:
                        glowColor = Color.BLACK;
                }
                int glowSize = 3;
                icon = new JLabel(
                    ImageBuffer.getOutlinedIconWithGlow(
                        statValue,
                        20,
                        glowColor,
                        glowSize
                    )
                );
            }

            if (!enchantText.isEmpty()) {
                itemName += "<br>" + enchantText.replace("\n", "<br>");
            }
            icon.setToolTipText("<html>" + itemName + "</html>");
            panel.add(icon);
        }

        mainPanel.add(panel);
    }

    private static void displayBagIcon(
        Entity entity,
        long lootTime,
        String timestamp,
        JPanel panel
    ) {
        int bag = entity.objectType;
        JLabel icon = new JLabel(ImageBuffer.getOutlinedIcon(bag, 20));
        String name = timestamp;
        name += "<br>" + IdToAsset.objectName(bag);
        if (lootTime > 0) {
            name += "<br>Loot drop bonus 50%";
        }
        icon.setToolTipText("<html>" + name + "</html>");

        panel.add(icon);
    }

    private static void displayMobIcon(int mob, int sharedLoot, JPanel panel) {
        JLabel icon = new JLabel(ImageBuffer.getOutlinedIcon(mob, 20));
        String name = "Unknown";
        if (mob != 100) {
            name = IdToAsset.objectName(mob);
        }
        if (sharedLoot != 0) {
            name += "<br>Shared loot: " + sharedLoot + " players";
        }
        icon.setToolTipText("<html>" + name + "</html>");
        panel.add(icon);
    }

    private static void displayDungeonIcon(MapInfoPacket map, JPanel panel) {
        displayDungeonIcon(map, panel, map != null && "Moonlight Village".equals(map.name) && data != null ? data.getMoonlightFlameCount() : 0);
    }

    private static void displayDungeonIcon(MapInfoPacket map, JPanel panel, int flames) {
        int dungeon = 100;
        String dungeonName = "Unknown";
        String dungeonModifiers = "";
        if (map != null) {
            dungeonName = map.name;
            dungeonModifiers = dungeonBuff(
                ParseDungeon.getModifiersString(map)
            );
            dungeon = ParseDungeon.getPortalId(dungeonName);
            if (dungeon == -1) {
                CharacterStatistics cs = CharacterStatistics.statByName(
                    dungeonName
                );
                if (cs != null) {
                    dungeon = cs.getSpriteId();
                } else {
                    dungeon = 100;
                }
            }
        }

        JLabel icon = new JLabel(ImageBuffer.getOutlinedIcon(dungeon, 20));
        if (!dungeonModifiers.isEmpty()) {
            dungeonName += "<br>" + dungeonModifiers;
        }

        if (flames > 0) dungeonName += "<br>Flames: " + flames;

        icon.setToolTipText("<html>" + dungeonName + "</html>");
        panel.add(icon);
    }

    private static String dungeonBuff(String buffs) {
        String b = "";
        if (buffs == null || buffs.isEmpty()) return b;
        for (String s : buffs.split(";")) {
            if (s.contains("REWARDSBOOSTBOSS_")) {
                b += " Boss " + getaChar(s) * 15 + "% ";
            }
            if (s.contains("REWARDSBOOSTMINIONS_")) {
                b += " Minions " + (getaChar(s) + 2) * 50 + "% ";
            }
            if (s.contains("REWARDSDECREASEMINIONS_")) {
                b += " Minions " + (4 - getaChar(s)) * 25 + "% ";
            }
        }
        return b;
    }

    private static int getaChar(String s) {
        return s.charAt(s.length() - 1) - 48;
    }

    public static void editFont(Font font) {
        LootGUI view = INSTANCE;
        if (view != null) onEdt(() -> view.handleFontUpdate(font));
    }

    public static void lootSharing(boolean b) {
        LootGUI view = INSTANCE;
        if (view == null) SendLoot.session().setEnabled(!b);
        else view.sharing.setEnabled(!b);
    }

    private static String time() {
        return LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("HH:mm:ss")
        );
    }

    private static Font getMainFont() {
        if (mainFont == null) {
            mainFont = ContentStyle.body();
        }
        return mainFont;
    }

    private void handleFontUpdate(Font font) {
        mainFont = font;
        sharingStatus.setFont(ContentStyle.metadata(font));
        sharingDetails.setFont(sharingStatus.getFont());
        updateFonts(lootPanel, font);
        safeRefreshPanel();
    }

    private void safeRefreshPanel() {
        lootPanel.revalidate();
        lootPanel.repaint();
    }

    private static void updateFonts(Container root, Font font) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel) child.setFont(font);
            if (child instanceof Container) updateFonts((Container)child, font);
        }
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    /** Only the stats used by the legacy row cross the capture/EDT boundary, never combat graphs. */
    private static final class LootEntry {
        final MapInfoPacket map;
        final Entity bag, player;
        final int mob, sharedLoot, exaltBonus, flames;
        final long lootTime;
        final String timestamp = time();
        int number;

        LootEntry(MapInfoPacket source, Entity bag, Entity dropper, Entity player, long time) {
            this.bag = copyStats(bag);
            this.player = copyStats(player);
            mob = dropper == null ? 100 : dropper.objectType;
            sharedLoot = dropper == null ? 0 : dropper.playersRemainAtKill();
            exaltBonus = CharacterClass.weaponClasses(player.objectType) == null ? -1 : RealmCharacter.exaltLootBonus(player.objectType);
            lootTime = player.lootDropTime(time);
            if (source == null) map = null;
            else {
                map = new MapInfoPacket(); map.name = source.name;
                map.dungeonModifiers = source.dungeonModifiers; map.dungeonModifiers2 = source.dungeonModifiers2;
                map.dungeonModifiers3 = source.dungeonModifiers3; map.dungeonModifiers4 = source.dungeonModifiers4;
                map.dungeonGrade = source.dungeonGrade;
            }
            flames = source != null && "Moonlight Village".equals(source.name) ? data.getMoonlightFlameCount() : 0;
            if (flames > 0) {
                TomatoData capturedData = data;
                onEdt(() -> {
                    Timer reset = new Timer(5000, e -> capturedData.resetMoonlightFlames());
                    reset.setRepeats(false); reset.start();
                });
            }
        }

        private static Entity copyStats(Entity source) {
            Entity copy = new Entity(null, source.id, 0); copy.objectType = source.objectType;
            for (int slot = 0; slot < 8; slot++) copyStat(source, copy, StatType.byOrdinal(StatType.INVENTORY_0_STAT.get() + slot));
            copyStat(source, copy, StatType.UNIQUE_DATA_STRING);
            copyStat(source, copy, StatType.SKIN_ID);
            copyStat(source, copy, StatType.SEASONAL);
            return copy;
        }

        private static void copyStat(Entity source, Entity copy, StatType type) {
            StatData stat = source.stat.get(type);
            if (stat == null) return;
            StatData value = new StatData();
            value.statType = type; value.statTypeNum = type.get();
            value.statValue = stat.statValue; value.statValueTwo = stat.statValueTwo; value.stringStatValue = stat.stringStatValue;
            copy.stat.set(type, value);
        }
    }
}
