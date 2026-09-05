package nextvisit.api.snapshots;

import nextvisit.engine.Axis;

/** 보호자가 한 항목에 대해 보낸 값. 적용되지 않는 축은 null. */
public record ItemInput(Integer level, Integer aid, Integer consistency, Integer hand, String note) {
    public Integer axis(Axis axis) {
        return switch (axis) {
            case LEVEL -> level;
            case AID -> aid;
            case CONSISTENCY -> consistency;
            case HAND -> hand;
        };
    }
}
