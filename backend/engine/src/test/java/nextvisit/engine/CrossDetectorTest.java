package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrossDetectorTest {

    static final ObservationSet SET = ObservationSet.STROKE;

    static ItemVerdicts item(String code, String level) {
        Map<Axis, Verdict> m = new EnumMap<>(Axis.class);
        m.put(Axis.LEVEL, SilenceGate.judge(TestTrajectories.parse(level)));
        return new ItemVerdicts(code, m);
    }

    static ItemVerdicts item(String code, String level, Axis extra, String extraSpec) {
        Map<Axis, Verdict> m = new EnumMap<>(Axis.class);
        m.put(Axis.LEVEL, SilenceGate.judge(TestTrajectories.parse(level)));
        m.put(extra, SilenceGate.judge(TestTrajectories.parse(extraSpec)));
        return new ItemVerdicts(code, m);
    }

    static List<Detection> ofType(List<Detection> all, DetectionType t) {
        return all.stream().filter(d -> d.type() == t).toList();
    }

    @Test
    void riseVsStallPairsEachSustainedUpWithEachNoChange() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("toilet", "2 2 3 3 3 3"),
            item("ambulation", "2 2 2 2 2 2"),
            item("bathing", "1 1 1 1 1 1")), List.of(), List.of());

        List<Detection> a = ofType(out, DetectionType.RISE_VS_STALL);
        assertEquals(2, a.size());
        assertEquals(List.of("toilet", "ambulation"), a.get(0).items());
        assertEquals(List.of("toilet", "bathing"), a.get(1).items());
        assertEquals(4, a.get(0).duration());  // X(toilet)의 duration
    }

    @Test
    void riseVsStallIgnoresGroups() {
        // 검증된 질문 1: toilet(selfcare) + ambulation(mobility)
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("toilet", "2 2 3 3 3 3"),
            item("ambulation", "2 2 2 2 2 2")), List.of(), List.of());
        assertEquals(1, ofType(out, DetectionType.RISE_VS_STALL).size());
    }

    @Test
    void riseVsStallNeedsSustainedNotObservedOnce() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("toilet", "2 2 2 2 2 3"),
            item("ambulation", "2 2 2 2 2 2")), List.of(), List.of());
        assertTrue(ofType(out, DetectionType.RISE_VS_STALL).isEmpty());
    }

    @Test
    void riseVsDeclinePairsSustainedUpWithSustainedDown() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("transfer", "1 1 1 2 2 2"),
            item("dressing", "3 3 3 2 2 2"),
            item("feeding", "2 2 2 3 3 3")), List.of(), List.of());

        List<Detection> b = ofType(out, DetectionType.RISE_VS_DECLINE);
        assertEquals(2, b.size());
        assertEquals(List.of("transfer", "dressing"), b.get(0).items());
        assertEquals(List.of("feeding", "dressing"), b.get(1).items());
        assertEquals(3, b.get(0).duration());
    }

    @Test
    void fluctuatingIsNeitherRiseNorStall() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("grooming", "2 2 3 3 2 3"),
            item("ambulation", "2 2 2 2 2 2"),
            item("dressing", "3 3 3 2 2 2")), List.of(), List.of());
        assertTrue(ofType(out, DetectionType.RISE_VS_STALL).isEmpty());
        assertTrue(ofType(out, DetectionType.RISE_VS_DECLINE).isEmpty());
    }
}
