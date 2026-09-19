package packets.data.enums;

import java.io.Serializable;

public enum PartyActionResultType implements Serializable {
    None(0),
    Failed(1),
    Kicked(2),
    KickNotFound(3),
    PromotedToLeader(4),
    PromoteNotFound(5),
    LeftParty(6);

    private final int index;

    PartyActionResultType(int i) {
        index = i;
    }

    public int get() {
        return index;
    }

    public static PartyActionResultType byOrdinal(byte ord) {
        for (PartyActionResultType value : values()) {
            if (value.index == ord) return value;
        }
        return null;
    }
}
