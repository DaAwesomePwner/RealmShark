package packets.reader;

import packets.Packet;
import packets.PacketType;
import util.Util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Custom buffer class to deserialize the rotmg packets.
 */
public class BufferReader {
    protected ByteBuffer buffer;
    private String field = "packet";
    private int fieldStart;
    private Integer declaredLength;

    /** Only decoder-authored field paths belong here, never values from the wire. */
    public void field(String name) { field = name; fieldStart = buffer.position(); declaredLength = null; }
    public String field() { return field; }
    public int fieldStart() { return fieldStart; }
    public Integer declaredLength() { return declaredLength; }
    public int checkedCount(int count, int minimumBytes) {
        declaredLength = count;
        if (count < 0 || minimumBytes < 1 || count > buffer.remaining() / minimumBytes)
            throw new IllegalArgumentException("Invalid collection length");
        return count;
    }

    public BufferReader(ByteBuffer data) {
        buffer = data;
    }

    /**
     * Returns the buffer size.
     *
     * @return size of the buffer.
     */
    public int size() {
        return buffer.capacity();
    }

    /**
     * Internal index of the buffer.
     *
     * @return Returns the internal index the buffer is at.
     */
    public int getIndex() {
        return buffer.position();
    }

    /**
     * Gets the remaining bytes from current index.
     *
     * @return number of bytes remaining from current index.
     */
    public int getRemainingBytes() {
        return buffer.remaining();
    }

    /**
     * Deserialize a boolean.
     *
     * @return Returns a boolean that have been deserialized.
     */
    public boolean readBoolean() {
        return buffer.get() != 0;
    }

    /**
     * Deserialize a byte.
     *
     * @return Returns the byte that have been deserialized.
     */
    public byte readByte() {
        return buffer.get();
    }

    /**
     * Deserialize an unsigned byte.
     *
     * @return Returns an integer containing an unsigned byte that have
     * been deserialized.
     */
    public int readUnsignedByte() {
        return Byte.toUnsignedInt(buffer.get());
    }

    /**
     * Deserialize a short.
     *
     * @return Returns the short that have been deserialized.
     */
    public short readShort() {
        return buffer.getShort();
    }

    /**
     * Deserialize an unsigned short.
     *
     * @return Returns an integer containing an unsigned short that have
     * been deserialized.
     */
    public int readUnsignedShort() {
        return Short.toUnsignedInt(buffer.getShort());
    }

    /**
     * Deserialize an integer.
     *
     * @return Returns the integer that have been deserialized.
     */
    public int readInt() {
        return buffer.getInt();
    }

    /**
     * Deserialize an unsigned integer.
     *
     * @return Returns an integer containing an unsigned integer that have
     * been deserialized.
     */
    public long readUnsignedInt() {
        return Integer.toUnsignedLong(buffer.getInt());
    }

    /**
     * Deserialize a float.
     *
     * @return Returns the float that have been deserialized.
     */
    public float readFloat() {
        return buffer.getFloat();
    }

    /**
     * Deserialize a string.
     *
     * @return Returns the string that have been deserialized.
     */
    public String readString() {
        int len = checkedCount(readUnsignedShort(), 1);
        byte[] str = new byte[len];
        buffer.get(str);
        return new String(str, StandardCharsets.UTF_8);
    }

    /**
     * Deserialize a string using UTF32 (more characters that is
     * never found in-game) not used as far as I'm aware.
     *
     * @return Returns the string that have been deserialized.
     */
    public String readStringUTF32() {
        int len = checkedCount(readInt(), 1);
        byte[] str = new byte[len];
        buffer.get(str);
        return new String(str, StandardCharsets.UTF_8);
    }

    /**
     * Deserialize a byte array
     */
    public byte[] readByteArray() {
        byte[] out = new byte[checkedCount(readUnsignedShort(), 1)];
        buffer.get(out);
        return out;
    }

    /**
     * Deserialize a byte array.
     *
     * @param bytes Number of bytes that is contained in the array.
     * @return Returns a byte array that have been deserialized.
     */
    public byte[] readBytes(int bytes) {
        byte[] out = new byte[checkedCount(bytes, 1)];
        buffer.get(out);
        return out;
    }

    /**
     * Rotmg deserializer of a compressed int.
     *
     * @return Returns the int that have been deserialized.
     */
    public int readCompressedInt() {
        int uByte = readUnsignedByte();
        boolean isNegative = (uByte & 64) != 0;
        int shift = 6;
        long value = uByte & 63;

        while ((uByte & 128) != 0) {
            if (shift > 27) throw new IllegalArgumentException("Compressed integer too long");
            uByte = readUnsignedByte();
            value |= (long)(uByte & 127) << shift;
            shift += 7;
        }

        if (value > (isNegative ? 2147483648L : Integer.MAX_VALUE))
            throw new IllegalArgumentException("Compressed integer overflow");
        return (int)(isNegative ? -value : value);
    }

    /**
     * Debug print command to print the buffer byte data.
     *
     * @return String representation of buffer data in a byte array format.
     */
    public String printBufferArray() {
        return Arrays.toString(buffer.array());
    }

    /**
     * Returns the remaining bytes in the buffer from the current index.
     *
     * @return Returns the remaining bytes.
     */
    public byte[] giveRemainingArray() {
        return Arrays.copyOfRange(buffer.array(), getIndex(), size());
    }

    /**
     * Error check if buffer is not finished.
     *
     * @param packetType
     * @param type
     */

    // -------------- packet error checking -------------

    /**
     * Checks if the buffer have finished reading all bytes.
     */
    public boolean isBufferFullyParsed() {
        if (buffer.hasRemaining()) {
            return false;
        }
        return true;
    }

    /**
     * Prints an error log and the data in the buffer.
     *
     * @param packet Packet type.
     */
    public void printError(Packet packet) {
        Util.printLogs(PacketType.byClass(packet) + " : " + buffer.position() + "/" + buffer.capacity());
        Util.printLogs(Arrays.toString(buffer.array()));
    }

    public String toString() {
        return Arrays.toString(buffer.array());
    }
}
