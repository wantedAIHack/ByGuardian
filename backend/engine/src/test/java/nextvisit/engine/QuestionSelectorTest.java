package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionSelectorTest {

    static final ObservationSet SET = ObservationSet.STROKE;
    static final SignalPattern GRIMACE = new SignalPattern(new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE), 3, 4, true);
    static final SignalPattern GUARD = new SignalPattern(new SignalKey(SignalAction.STANDING, SignalKind.GUARDING), 3, 4, true);

    static Detection items(DetectionType t, int duration, String... codes) {
        return Detection.ofItems(t, List.of(codes), duration);
    }

    @Test
    void roundRobinByPriorityThenDuration() {
        List<Detection> all = List.of(
            items(DetectionType.FLUCTUATION, 6, "grooming"),
            items(DetectionType.RISE_VS_STALL, 3, "transfer", "ambulation"),
            items(DetectionType.RISE_VS_STALL, 4, "toilet", "ambulation"),
            items(DetectionType.RISE_VS_DECLINE, 4, "toilet", "dressing"),
            Detection.ofSignal(DetectionType.STALL_WITH_PAIN, "ambulation", 6, GUARD),
            Detection.ofSignal(DetectionType.STALL_WITH_PAIN, "ambulation", 6, GRIMACE),
            items(DetectionType.HAND_DISUSE, 3, "feeding"));

        List<Detection> picked = QuestionSelector.select(all, SET);

        assertEquals(3, picked.size());
        assertEquals(DetectionType.RISE_VS_STALL, picked.get(0).type());
        assertEquals(List.of("toilet", "ambulation"), picked.get(0).items());   // duration 4 > 3
        assertEquals(DetectionType.STALL_WITH_PAIN, picked.get(1).type());
        assertEquals(SignalKind.GRIMACE, picked.get(1).signal().kind());        // GRIMACE < GUARDING
        assertEquals(DetectionType.HAND_DISUSE, picked.get(2).type());
    }

    @Test
    void sameTypeTieBreaksByItemOrder() {
        List<Detection> all = List.of(
            items(DetectionType.RISE_VS_STALL, 4, "toilet", "bathing"),
            items(DetectionType.RISE_VS_STALL, 4, "toilet", "ambulation"),
            items(DetectionType.RISE_VS_STALL, 4, "toilet", "stairs"));
        List<Detection> picked = QuestionSelector.select(all, SET);
        assertEquals(List.of("toilet", "ambulation"), picked.get(0).items());
        assertEquals(List.of("toilet", "stairs"), picked.get(1).items());
        assertEquals(List.of("toilet", "bathing"), picked.get(2).items());
    }

    @Test
    void secondPassFillsRemainingSlotsFromSameTypes() {
        // 종류가 둘뿐이고 감지가 넷이면 (a),(b),(a) 순
        List<Detection> all = List.of(
            items(DetectionType.RISE_VS_STALL, 4, "toilet", "ambulation"),
            items(DetectionType.RISE_VS_STALL, 4, "toilet", "stairs"),
            items(DetectionType.RISE_VS_DECLINE, 4, "toilet", "dressing"),
            items(DetectionType.RISE_VS_DECLINE, 3, "transfer", "dressing"));
        List<Detection> picked = QuestionSelector.select(all, SET);
        assertEquals(List.of(DetectionType.RISE_VS_STALL, DetectionType.RISE_VS_DECLINE, DetectionType.RISE_VS_STALL),
            picked.stream().map(Detection::type).toList());
    }

    @Test
    void fewerThanThreeReturnsAll() {
        List<Detection> all = List.of(items(DetectionType.FLUCTUATION, 6, "grooming"));
        assertEquals(1, QuestionSelector.select(all, SET).size());
    }

    @Test
    void emptyStaysEmpty() {
        assertTrue(QuestionSelector.select(List.of(), SET).isEmpty());
    }
}
