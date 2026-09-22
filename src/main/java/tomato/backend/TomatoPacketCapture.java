package tomato.backend;

import packets.Packet;
import packets.incoming.*;
import packets.outgoing.*;
import tomato.backend.data.CrucibleBonusManager;
import tomato.backend.data.TomatoData;
import tomato.gui.dps.DpsGUI;
import tomato.gui.stats.FameTablePanel;
import tomato.realmshark.Sound;

/**
 * Main packet handling class for incoming packets.
 */
public class TomatoPacketCapture implements Controller {

    private TomatoData data;

    public TomatoPacketCapture(TomatoData data) {
        this.data = data;
    }

    /**
     * @param packet incoming packets to be processed.
     */
    public void packetCapture(Packet packet) {
        if (packet instanceof MovePacket) {
            MovePacket p = (MovePacket) packet;
            data.updatePlayersPos(p);
        } else if (packet instanceof NewTickPacket) {
            NewTickPacket p = (NewTickPacket) packet;
            data.updateNewTick(p);
            data.rememberCharacter();
            DpsGUI.updateNewTickPacket(data);
            data.logPacket(packet);

            // Update player crucible bonus from stat 155
            if (data.player != null) {
                CrucibleBonusManager.updatePlayerCrucibleBonus(data.player);
            }
        } else if (packet instanceof UpdatePacket) {
            UpdatePacket p = (UpdatePacket) packet;
            data.update(p);
            data.rememberCharacter();
            data.logPacket(packet);

            // Update player crucible bonus from stat 155
            if (data.player != null) {
                CrucibleBonusManager.updatePlayerCrucibleBonus(data.player);
            }
        } else if (packet instanceof PlayerShootPacket) {
            PlayerShootPacket p = (PlayerShootPacket) packet;
            data.playerShoot(p);
            data.logPacket(packet);
            //System.out.println(packet);
        } else if (packet instanceof ServerPlayerShootPacket) {
            ServerPlayerShootPacket p = (ServerPlayerShootPacket) packet;

            data.serverPlayerShoot(p);
            data.logPacket(packet);
            //System.out.println(packet);
        } else if (packet instanceof EnemyHitPacket) {
            EnemyHitPacket p = (EnemyHitPacket) packet;

            data.enemtyHit(p);
            data.logPacket(packet);
            //System.out.println(packet);
        } else if (packet instanceof DamagePacket) {
            DamagePacket p = (DamagePacket) packet;
            data.damage(p);
            data.logPacket(packet);
        } else if (packet instanceof PlayerHitPacket) {
            PlayerHitPacket p = (PlayerHitPacket) packet;
            data.userDamage(p);
        } else if (packet instanceof EnemyShootPacket) {
            EnemyShootPacket p = (EnemyShootPacket) packet;
            data.enemyProjectile(p);
        } else if (packet instanceof AoePacket) {
            AoePacket p = (AoePacket) packet;
            data.aoeDamage(p);
        } else if (packet instanceof GroundDamagePacket) {
            GroundDamagePacket p = (GroundDamagePacket) packet;
            data.groundDamage(p);
        } else if (packet instanceof AccountListPacket) {
            tomato.gui.chat.ChatGUI.observeAccountList((AccountListPacket) packet);
        } else if (packet instanceof TextPacket) {
            TextPacket p = (TextPacket) packet;
            data.text(p);
            data.logPacket(packet);
        } else if (packet instanceof StasisPacket) {
            StasisPacket p = (StasisPacket) packet;
            SecurityAbilityUseCheck.stasis(p, data);
        } else if (packet instanceof MapInfoPacket) {
            MapInfoPacket p = (MapInfoPacket) packet;
            data.setNewRealm(p);
            DpsGUI.updateMapPacket(data);
            data.logPacket(packet);
            // Notify fame table panel about map change
            FameTablePanel.handleMapChange(p.displayName);
        } else if (packet instanceof CreateSuccessPacket) {
            CreateSuccessPacket p = (CreateSuccessPacket) packet;
            data.setUserId(p.objectId, p.charId, p.str);
            data.logPacket(packet);
            data.webRequest();
        } else if (packet instanceof ExaltationUpdatePacket) {
            ExaltationUpdatePacket p = (ExaltationUpdatePacket) packet;
            data.exaltUpdate(p);
        } else if (packet instanceof NotificationPacket) {
            NotificationPacket p = (NotificationPacket) packet;
            data.notification(p);
        } else if (packet instanceof VaultContentPacket) {
            VaultContentPacket p = (VaultContentPacket) packet;
            data.vaultPacketUpdate(p);
        } else if (packet instanceof HelloPacket) {
            tomato.gui.chat.ChatGUI.resetObservedIgnores();
            HelloPacket p = (HelloPacket) packet;
            data.updateToken(p.accessToken);
        } else if (packet instanceof QuestFetchResponsePacket) {
            data.quests((QuestFetchResponsePacket) packet);
        } else if (packet instanceof TradeRequestedPacket) {
            if (Sound.trade.isEnabled()) {
                Sound.trade.play();
            }
        } else if (isCrucibleResponsePacket(packet)) {
            // Handle CrucibleResponsePacket to extract damage multipliers
            System.out.println("CRUCIBLE_RESPONSE_PACKET: " + packet);
            CrucibleBonusManager.processCrucibleResponse(packet);
        }
    }

    /**
     * Checks if the packet is a CrucibleResponsePacket
     * Uses multiple detection methods to handle different packet structures
     */
    private boolean isCrucibleResponsePacket(Packet packet) {
        String className = packet.getClass().getSimpleName();

        // Method 1: Check class name patterns
        if (className.contains("Crucible") && className.contains("Response")) {
            return true;
        }

        // Method 2: Check toString content for JSON structure
        String packetString = packet.toString();
        if (
            packetString.contains("\"array\"") &&
            packetString.contains("\"id\"")
        ) {
            return true;
        }

        // Method 3: Try reflection to check for JSON data fields
        try {
            Class<?> packetClass = packet.getClass();
            for (java.lang.reflect.Field field : packetClass.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(packet);
                if (value instanceof String) {
                    String stringValue = (String) value;
                    if (
                        stringValue.contains("\"array\"") &&
                        stringValue.contains("\"id\"")
                    ) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore reflection errors
        }

        return false;
    }

    @Override
    public void dispose() {}
}
