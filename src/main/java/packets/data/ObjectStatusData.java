package packets.data;

import packets.reader.BufferReader;
import util.Util;

import java.io.Serializable;

public class ObjectStatusData implements Serializable {
    /**
     * The object id of the object which this status is for
     */
    public int objectId;
    /**
     * The position of the object which this status is for
     */
    public WorldPosData pos;
    /**
     * A list of stats for the object which this status is for
     */
    public StatData[] stats;

    /**
     * Deserializer method to extract data from the buffer.
     *
     * @param buffer Data that needs deserializing.
     * @return Returns this object after deserializing.
     */
    public ObjectStatusData deserialize(BufferReader buffer) {
        String path = buffer.field();
        buffer.field(path + ".objectId");
        objectId = buffer.readCompressedInt();
        buffer.field(path + ".position");
        pos = new WorldPosData().deserialize(buffer);

        buffer.field(path + ".stats.length");
        stats = new StatData[buffer.checkedCount(buffer.readCompressedInt(), 3)];
        for (int i = 0; i < stats.length; i++) {
            buffer.field(path + ".stats[" + i + "]");
            stats[i] = new StatData().deserialize(buffer);
        }

        buffer.field(path);
        return this;
    }

    @Override
    public String toString() {
        return "    Id=" + objectId + " Loc=(" + pos.x + ", " + pos.y + ")" +
                (stats.length == 0 ? "" : Util.showAll(stats));
    }
}
