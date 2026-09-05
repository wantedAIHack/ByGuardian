package nextvisit.api.snapshots;

import java.util.List;
import java.util.Map;
import nextvisit.engine.Axis;

/** 스냅샷 JSON 본문. 설계 2.2. 값마다 출처(CONFIRMED | CARRIED)가 붙는다. */
public record SnapshotBody(
    Map<String, ItemValues> items,
    Map<String, List<String>> painSignal,
    Integer sleep,
    FreeNote freeNote
) {
    public record Val(int value, String source) {}

    public record ItemValues(Val level, Val aid, Val consistency, Val hand, String note) {
        public Val axis(Axis axis) {
            return switch (axis) {
                case LEVEL -> level;
                case AID -> aid;
                case CONSISTENCY -> consistency;
                case HAND -> hand;
            };
        }
    }

    public record FreeNote(String text, String timeTag) {}
}
