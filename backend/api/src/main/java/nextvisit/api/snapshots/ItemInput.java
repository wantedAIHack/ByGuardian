package nextvisit.api.snapshots;

import nextvisit.engine.Axis;
import java.util.Map;
import java.util.List;

/** 보호자가 한 항목에 대해 보낸 값. 적용되지 않는 축은 null. */
public record ItemInput(Integer level, Integer aid, Integer consistency, Integer hand, String note, Integer questionnaireVersion, Map<String, List<String>> answers) {
    public ItemInput(Integer level, Integer aid, Integer consistency, Integer hand, String note) {
        this(level, aid, consistency, hand, note, null, null);
    }
    public Integer axis(Axis axis) {
        return switch (axis) {
            case LEVEL -> level;
            case AID -> aid;
            case CONSISTENCY -> consistency;
            case HAND -> hand;
        };
    }
}
