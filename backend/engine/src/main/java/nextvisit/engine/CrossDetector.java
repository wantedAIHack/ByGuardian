package nextvisit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** README §7 교차 감지. 규칙 엔진. 문장은 만들지 않는다. */
public final class CrossDetector {

    private CrossDetector() {}

    public static final int FLUCTUATION_REVERSALS = 2;
    public static final int TIME_WINDOW_WEEKS = 4;
    public static final int TIME_THRESHOLD = 3;

    public static List<Detection> detect(
        ObservationSet set,
        List<ItemVerdicts> verdicts,
        List<SignalPattern> patterns,
        List<WeeklyNote> notes
    ) {
        List<ItemVerdicts> items = verdicts.stream()
            .sorted(Comparator.comparingInt(v -> set.order(v.code())))
            .toList();
        List<SignalPattern> firing = patterns.stream().filter(SignalPattern::pattern).toList();

        List<Detection> out = new ArrayList<>();
        riseVsStall(items, out);
        riseVsDecline(items, out);
        participationVsSignal(set, items, firing, out);
        fluctuation(items, out);
        axisRules(items, out);
        timeOfDay(notes, out);
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

    /** (c) 세 줄. 패턴마다 정체 항목 하나·상승 항목 하나와 짝. 패턴이 없을 때만 하락 항목 전부. */
    private static void participationVsSignal(
        ObservationSet set, List<ItemVerdicts> items, List<SignalPattern> firing, List<Detection> out
    ) {
        Comparator<ItemVerdicts> longestFirst = Comparator
            .comparingInt((ItemVerdicts v) -> v.level().duration()).reversed()
            .thenComparingInt(v -> set.order(v.code()));

        List<ItemVerdicts> stalled = items.stream()
            .filter(v -> v.level().status() == Status.NO_CHANGE).sorted(longestFirst).toList();
        List<ItemVerdicts> risers = items.stream()
            .filter(v -> v.level().is(Status.SUSTAINED, Direction.UP)).sorted(longestFirst).toList();

        for (SignalPattern p : firing) {
            if (!stalled.isEmpty()) {
                ItemVerdicts y = stalled.get(0);
                out.add(Detection.ofSignal(DetectionType.STALL_WITH_PAIN, y.code(), y.level().duration(), p));
            }
        }
        EnumSet<SignalAction> seenActions = EnumSet.noneOf(SignalAction.class);
        for (SignalPattern p : firing) {
            if (!seenActions.add(p.key().action())) {
                continue;
            }
            if (!risers.isEmpty()) {
                ItemVerdicts x = risers.get(0);
                out.add(Detection.ofSignal(DetectionType.RISE_WITH_PAIN, x.code(), x.level().duration(), p));
            }
        }
        if (firing.isEmpty()) {
            for (ItemVerdicts x : items) {
                if (x.level().is(Status.SUSTAINED, Direction.DOWN)) {
                    out.add(Detection.ofItems(DetectionType.DECLINE_NO_SIGNAL, List.of(x.code()), x.level().duration()));
                }
            }
        }
    }

    /** (d) level 축 방향 전환 2회 이상. duration = 기록된 전체 주 수. */
    private static void fluctuation(List<ItemVerdicts> items, List<Detection> out) {
        for (ItemVerdicts x : items) {
            if (x.level().reversals() >= FLUCTUATION_REVERSALS) {
                out.add(Detection.ofItems(DetectionType.FLUCTUATION, List.of(x.code()), x.level().trajectory().size()));
            }
        }
    }

    /** (f)(g)(h). 항목마다 각 규칙 최대 하나. 순서: 항목 표 순서, 그 안에서 f → g → h. */
    private static void axisRules(List<ItemVerdicts> items, List<Detection> out) {
        for (ItemVerdicts x : items) {
            Verdict level = x.level();
            x.axis(Axis.AID).ifPresent(aid -> {
                if (level.status() == Status.NO_CHANGE && aid.status() == Status.SUSTAINED) {
                    out.add(Detection.ofItems(DetectionType.AID_CHANGE, List.of(x.code()), aid.duration()));
                }
            });
            x.axis(Axis.HAND).ifPresent(hand -> {
                if (level.is(Status.SUSTAINED, Direction.UP) && hand.is(Status.SUSTAINED, Direction.DOWN)) {
                    out.add(Detection.ofItems(DetectionType.HAND_DISUSE, List.of(x.code()), hand.duration()));
                }
            });
            x.axis(Axis.CONSISTENCY).ifPresent(c -> {
                if (level.status() == Status.NO_CHANGE && c.is(Status.SUSTAINED, Direction.DOWN)) {
                    out.add(Detection.ofItems(DetectionType.CONSISTENCY_DROP, List.of(x.code()), c.duration()));
                }
            });
        }
    }

    /** (e) 1단계. 최근 기록된 4주 중 같은 시간대 태그 3주 이상. ANY·null은 세지 않는다. */
    private static void timeOfDay(List<WeeklyNote> notes, List<Detection> out) {
        List<WeeklyNote> sorted = new ArrayList<>(notes);
        sorted.sort(Comparator.comparingInt(WeeklyNote::week));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).week() == sorted.get(i - 1).week()) {
                throw new IllegalArgumentException("duplicate week: " + sorted.get(i).week());
            }
        }

        int from = Math.max(0, sorted.size() - TIME_WINDOW_WEEKS);
        List<WeeklyNote> window = sorted.subList(from, sorted.size());
        for (TimeTag tag : List.of(TimeTag.MORNING, TimeTag.AFTERNOON, TimeTag.EVENING)) {
            int n = (int) window.stream().filter(w -> w.tag() == tag).count();
            if (n >= TIME_THRESHOLD) {
                out.add(Detection.ofTime(tag, n, window.size()));
            }
        }
    }
}
