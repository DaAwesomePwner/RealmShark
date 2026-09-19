package packets.packetcapture.logger;

import org.junit.Test;
import packets.incoming.*;
import packets.PacketType;
import packets.reader.BufferReader;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DecoderDiagnosticsTest {
    @Test public void stringsUseUnsignedLengthsAndUtf8() {
        byte[] text=new byte[40000]; java.util.Arrays.fill(text,(byte)'a');
        ByteBuffer data=ByteBuffer.allocate(40002).putShort((short)40000).put(text); data.flip();
        assertEquals(40000,new BufferReader(data).readString().length());
        byte[] unicode="Å界".getBytes(StandardCharsets.UTF_8);
        data=ByteBuffer.allocate(unicode.length+2).putShort((short)unicode.length).put(unicode); data.flip();
        assertEquals("Å界",new BufferReader(data).readString());
    }
    @Test public void lengthsAndCompressedIntegersCannotAllocateOrWrapBeyondTheBuffer() {
        try { new BufferReader(ByteBuffer.wrap(new byte[]{127,-1})).readString(); fail(); } catch(IllegalArgumentException expected) {}
        try { new BufferReader(ByteBuffer.wrap(new byte[]{-128,-128,-128,-128,-128,0})).readCompressedInt(); fail(); } catch(IllegalArgumentException expected) {}
        assertEquals(-1,new BufferReader(ByteBuffer.wrap(new byte[]{65})).readCompressedInt());
        ByteBuffer limited=ByteBuffer.allocate(20); limited.limit(4);
        assertEquals(4,new BufferReader(limited).getRemainingBytes());
    }
    @Test public void vaultFailureNamesFieldAndPositionWithoutPayloadOrExceptionMessage() throws Exception {
        ByteBuffer data=ByteBuffer.allocate(20); data.put((byte)1);
        for(int i=0;i<5;i++)data.put((byte)0); data.put((byte)65); data.flip();
        BufferReader reader=new BufferReader(data); Exception failure=null;
        try {new VaultContentPacket().deserialize(reader); fail();} catch(Exception expected){failure=expected;}
        assertEquals("vaultContents.length",reader.field());
        DiscoveryLog log=new DiscoveryLog(null);
        log.decodeFailure(PacketType.VAULT_UPDATE.getIndex(),7,reader,new IllegalArgumentException("SECRET_PAYLOAD"));
        assertEquals("vaultContents.length",log.snapshot().events.get(0).values.get("field"));
        assertFalse(new com.google.gson.Gson().toJson(log.snapshot()).contains("SECRET_PAYLOAD"));
        assertNotNull(failure);
    }
    @Test public void shortStasisFrameStaysAFailureInsteadOfInventingDuration() throws Exception {
        BufferReader reader=new BufferReader(ByteBuffer.allocate(19));
        try {new StasisPacket().deserialize(reader); fail();} catch(BufferUnderflowException expected) {}
        assertEquals("stasisDuration",reader.field()); assertEquals(3,reader.getRemainingBytes());
    }
    @Test public void badTickReportsSpecificStatAndValidEmptyTickStillDecodes() throws Exception {
        ByteBuffer good=ByteBuffer.allocate(16); good.putInt(1).putInt(200).putInt(1000).putShort((short)12).putShort((short)0).flip();
        NewTickPacket packet=new NewTickPacket(); BufferReader reader=new BufferReader(good); packet.deserialize(reader);
        assertTrue(reader.isBufferFullyParsed()); assertEquals(200,packet.tickTime);
        ByteBuffer bad=ByteBuffer.allocate(29);
        bad.putInt(1).putInt(200).putInt(1000).putShort((short)12).putShort((short)1);
        bad.put((byte)1).putFloat(0).putFloat(0).put((byte)1).put((byte)31).putShort((short)100).flip();
        reader=new BufferReader(bad);
        try {new NewTickPacket().deserialize(reader); fail();} catch(IllegalArgumentException expected) {}
        assertEquals("status[0].stats[0].type31.value",reader.field());
    }
}
