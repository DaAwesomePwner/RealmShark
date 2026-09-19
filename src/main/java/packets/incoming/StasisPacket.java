package packets.incoming;

import packets.Packet;
import packets.reader.BufferReader;

import java.util.Arrays;

/**
 * Stasis blinking timer packet
 */
public class StasisPacket extends Packet {
    /**
     * Id of the entity put into stasis
     */
    public int entityId;
    /**
     * Unknown
     */
    public byte[] unknownByteArray;
    /**
     * Stasis duration in seconds
     */
    public float stasisDuration;

    @Override
    public void deserialize(BufferReader buffer) throws Exception {
        buffer.field("entityId");
        entityId = buffer.readInt();
        buffer.field("unknownByteArray");
        unknownByteArray = buffer.readBytes(12);
        buffer.field("stasisDuration");
        stasisDuration = buffer.readFloat();

    }

    @Override
    public String toString() {
        return "StasisPacket{" +
                "\n   entityId=" + entityId +
                "\n   unknownByte1=" + Arrays.toString(unknownByteArray) +
                "\n   stasisDuration=" + stasisDuration;
    }
}
