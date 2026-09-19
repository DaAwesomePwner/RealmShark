package packets.incoming;

import packets.Packet;
import packets.data.enums.PartyActionResultType;
import packets.reader.BufferReader;

/**
 * Unknown packet -52 / 204
 */
public class PartyActionResultPacket extends Packet {

    public int playerId;
    public PartyActionResultType actionId;

    @Override
    public void deserialize(BufferReader buffer) throws Exception {
        playerId = buffer.readShort();
        actionId = PartyActionResultType.byOrdinal(buffer.readByte());
    }

    @Override
    public String toString() {
        return "PartyActionResult{" +
                "\n   playerId=" + playerId +
                "\n   actionId=" + actionId;
    }
}
