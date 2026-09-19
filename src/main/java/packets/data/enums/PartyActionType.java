package packets.data.enums;

import java.io.Serializable;

public enum PartyActionType implements Serializable {
    None(0),
    Kick(1),
    Disconnect(2),
    PromoteToLeader(3),
    Refresh(4),
    GetPartyList(5),
    LeaveParty(6),
    TeleportTo(7);

    private final int index;

    PartyActionType(int i) {
        index = i;
    }

    public int get() {
        return index;
    }

    public static PartyActionType byOrdinal(byte ord) {
        for (PartyActionType o : PartyActionType.values()) {
            if (o.index == ord) {
                return o;
            }
        }
        return null;
    }
}
