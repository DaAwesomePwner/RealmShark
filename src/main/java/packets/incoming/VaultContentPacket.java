package packets.incoming;

import packets.Packet;
import packets.reader.BufferReader;

import java.util.Arrays;

/**
 * Received when the player enters or updates their vault
 */
public class VaultContentPacket extends Packet {
    /**
     * If this is the last vault packet
     */
    public boolean lastVaultPacket;
    /**
     * Vault chest object ID
     */
    public int vaultChestObjectId;
    /**
     * material  ID
     */
    public int materialChestObjectId;
    /**
     * Gift chest object ID
     */
    public int giftChestObjectId;
    /**
     * Potion storage object ID
     */
    public int potionStorageObjectId;
    /**
     * Seasonal spoils object ID
     */
    public int seasonalSpoilChestObjectId;
    /**
     * The contents of the players vault, sent as an array of item object IDs or -1 if the slot is empty
     */
    public int[] vaultContents;
    /**
     * The material contents of the player
     */
    int[] materialContents;
    /**
     * The contents of the player's gift vault
     */
    public int[] giftContents;
    /**
     * The contents of the player's potion vault
     */
    public int[] potionContents;
    /**
     * The contents of the player's seasonal spoils items
     */
    int[] seasonalSpoilContent;
    /**
     * The cost in gold for the next upgrade to the vault
     */
    public short vaultUpgradeCost;
    /**
     * The cost in gold for the next upgrade to the potion vault
     */
    public short potionUpgradeCost;
    /**
     * The current slot size of the player's potion vault
     */
    public short currentPotionMax;
    /**
     * The size of the player's potion vault after they purchase the current upgrade
     */
    public short nextPotionMax;
    /**
     * String indicating enchantments in player's vault
     */
    public String vaultChestEnchants;
    /**
     * String indicating enchantments in player's gift vault
     */
    public String giftChestEnchants;
    /**
     * String indicating enchantments in player's spoils chest vault
     */
    public String spoilsChestEnchants;
    /**
     * Cost to upgrade materials
     */
    public short materialUpgradeCost;

    @Override
    public void deserialize(BufferReader buffer) throws Exception {
        buffer.field("lastVaultPacket");
        lastVaultPacket = buffer.readBoolean();
        buffer.field("vaultChestObjectId");
        vaultChestObjectId = buffer.readCompressedInt();
        buffer.field("materialChestObjectId");
        materialChestObjectId = buffer.readCompressedInt();
        buffer.field("giftChestObjectId");
        giftChestObjectId = buffer.readCompressedInt();
        buffer.field("potionStorageObjectId");
        potionStorageObjectId = buffer.readCompressedInt();
        buffer.field("seasonalSpoilChestObjectId");
        seasonalSpoilChestObjectId = buffer.readCompressedInt();

        vaultContents = readItems(buffer, "vaultContents");
        materialContents = readItems(buffer, "materialContents");
        giftContents = readItems(buffer, "giftContents");
        potionContents = readItems(buffer, "potionContents");
        seasonalSpoilContent = readItems(buffer, "seasonalSpoilContent");

        buffer.field("vaultUpgradeCost");
        vaultUpgradeCost = buffer.readShort();
        buffer.field("materialUpgradeCost");
        materialUpgradeCost = buffer.readShort();
        buffer.field("potionUpgradeCost");
        potionUpgradeCost = buffer.readShort();
        buffer.field("currentPotionMax");
        currentPotionMax = buffer.readShort();
        buffer.field("nextPotionMax");
        nextPotionMax = buffer.readShort();

        buffer.field("vaultChestEnchants");
        vaultChestEnchants = buffer.readString();
        buffer.field("giftChestEnchants");
        giftChestEnchants = buffer.readString();
        buffer.field("spoilsChestEnchants");
        spoilsChestEnchants = buffer.readString();
    }

    private static int[] readItems(BufferReader buffer, String name) {
        buffer.field(name + ".length");
        int[] items = new int[buffer.checkedCount(buffer.readCompressedInt(), 1)];
        for (int i = 0; i < items.length; i++) {
            buffer.field(name + "[" + i + "]");
            items[i] = buffer.readCompressedInt();
        }
        return items;
    }

    @Override
    public String toString() {
        return "VaultContentPacket{" +
                "\n   lastVaultPacket=" + lastVaultPacket +
                "\n   vaultChestObjectId=" + vaultChestObjectId +
                "\n   materialChestObjectId=" + materialChestObjectId +
                "\n   giftChestObjectId=" + giftChestObjectId +
                "\n   potionStorageObjectId=" + potionStorageObjectId +
                "\n   seasonalSpoilChestObjectId=" + seasonalSpoilChestObjectId +
                "\n   vaultContents=" + Arrays.toString(vaultContents) +
                "\n   materialContents=" + Arrays.toString(materialContents) +
                "\n   giftContents=" + Arrays.toString(giftContents) +
                "\n   potionContents=" + Arrays.toString(potionContents) +
                "\n   seasonalSpoilContent=" + Arrays.toString(seasonalSpoilContent) +
                "\n   vaultUpgradeCost=" + vaultUpgradeCost +
                "\n   potionUpgradeCost=" + potionUpgradeCost +
                "\n   currentPotionMax=" + currentPotionMax +
                "\n   nextPotionMax=" + nextPotionMax +
                "\n   vaultChestEnchants=" + vaultChestEnchants +
                "\n   giftChestEnchants=" + giftChestEnchants +
                "\n   spoilsChestEnchants=" + spoilsChestEnchants +
                "\n   materialUpgradeCost=" + materialUpgradeCost;
    }
}