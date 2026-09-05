package nextvisit.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** README §7 끝 "질문 3개 고르기". LLM이 고르지 않는다. */
public final class QuestionSelector {

    public static final int MAX_QUESTIONS = 3;

    public static final List<DetectionType> PRIORITY = List.of(
        DetectionType.RISE_VS_STALL,
        DetectionType.STALL_WITH_PAIN,
        DetectionType.HAND_DISUSE,
        DetectionType.RISE_VS_DECLINE,
        DetectionType.AID_CHANGE,
        DetectionType.CONSISTENCY_DROP,
        DetectionType.FLUCTUATION,
        DetectionType.RISE_WITH_PAIN,
        DetectionType.DECLINE_NO_SIGNAL,
        DetectionType.TIME_OF_DAY
    );

    private QuestionSelector() {}

    public static List<Detection> select(List<Detection> all, ObservationSet set) {
        Comparator<Detection> withinType = Comparator
            .comparingInt(Detection::duration).reversed()
            .thenComparingInt(d -> d.items().isEmpty() ? Integer.MAX_VALUE : set.order(d.items().get(0)))
            .thenComparingInt(d -> d.items().size() < 2 ? Integer.MAX_VALUE : set.order(d.items().get(1)))
            .thenComparing(Detection::signal, Comparator.nullsLast(Comparator.<SignalKey>naturalOrder()));

        Map<DetectionType, Deque<Detection>> queues = new EnumMap<>(DetectionType.class);
        for (DetectionType t : PRIORITY) {
            Deque<Detection> q = new ArrayDeque<>(all.stream().filter(d -> d.type() == t).sorted(withinType).toList());
            queues.put(t, q);
        }

        List<Detection> picked = new ArrayList<>();
        boolean progressed = true;
        while (picked.size() < MAX_QUESTIONS && progressed) {
            progressed = false;
            for (DetectionType t : PRIORITY) {
                Deque<Detection> q = queues.get(t);
                if (!q.isEmpty()) {
                    picked.add(q.pollFirst());
                    progressed = true;
                    if (picked.size() == MAX_QUESTIONS) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(picked);
    }
}
