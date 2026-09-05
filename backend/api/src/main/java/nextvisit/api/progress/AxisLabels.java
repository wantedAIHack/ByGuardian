package nextvisit.api.progress;

import java.util.List;
import nextvisit.engine.Axis;

/** 설계 7절 축 라벨. */
public final class AxisLabels {
    public static final List<Axis> ORDER = List.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY, Axis.HAND);

    private AxisLabels() {}

    public static String of(Axis axis) {
        return switch (axis) {
            case LEVEL -> "도움 수준";
            case AID -> "보조 도구";
            case CONSISTENCY -> "한 주 일관성";
            case HAND -> "마비 쪽 손";
        };
    }
}
