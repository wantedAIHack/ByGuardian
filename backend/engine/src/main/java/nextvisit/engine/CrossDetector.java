package nextvisit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** README §7 교차 감지. 규칙 엔진. 문장은 만들지 않는다. */
public final class CrossDetector {

    private CrossDetector() {}

    public static List<Detection> detect(
        ObservationSet set,
        List<ItemVerdicts> verdicts,
        List<SignalPattern> patterns,
        List<WeeklyNote> notes
    ) {
        List<ItemVerdicts> items = verdicts.stream()
            .sorted(Comparator.comparingInt(v -> set.order(v.code())))
            .toList();

        List<Detection> out = new ArrayList<>();
        riseVsStall(items, out);
        riseVsDecline(items, out);
        return List.copyOf(out);
    }

    /** (a) X sustained UP, Y no_change. duration = X.duration. */
    private static void riseVsStall(List<ItemVerdicts> items, List<Detection> out) {
        for (ItemVerdicts x : items) {
            if (!x.level().is(Status.SUSTAINED, Direction.UP)) {
                continue;
            }
            for (ItemVerdicts y : items) {
                if (y.level().status() == Status.NO_CHANGE) {
                    out.add(Detection.ofItems(DetectionType.RISE_VS_STALL,
                        List.of(x.code(), y.code()), x.level().duration()));
                }
            }
        }
    }

    /** (b) X sustained UP, Y sustained DOWN. duration = X.duration. */
    private static void riseVsDecline(List<ItemVerdicts> items, List<Detection> out) {
        for (ItemVerdicts x : items) {
            if (!x.level().is(Status.SUSTAINED, Direction.UP)) {
                continue;
            }
            for (ItemVerdicts y : items) {
                if (y.level().is(Status.SUSTAINED, Direction.DOWN)) {
                    out.add(Detection.ofItems(DetectionType.RISE_VS_DECLINE,
                        List.of(x.code(), y.code()), x.level().duration()));
                }
            }
        }
    }
}
