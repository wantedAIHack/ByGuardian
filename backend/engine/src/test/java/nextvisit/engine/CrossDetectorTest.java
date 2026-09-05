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

    static final SignalKey STAND_GRIMACE = new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE);
    static final SignalKey STAND_GUARD = new SignalKey(SignalAction.STANDING, SignalKind.GUARDING);
    static final SignalPattern GRIMACE_3_OF_4 = new SignalPattern(STAND_GRIMACE, 3, 4, true);
    static final SignalPattern GUARD_3_OF_4 = new SignalPattern(STAND_GUARD, 3, 4, true);
    static final SignalPattern GRIMACE_1_OF_4 = new SignalPattern(STAND_GRIMACE, 1, 4, false);

    @Test
    void stallWithPainPairsPatternWithLongestStalledItem() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("ambulation", "2 2 2 2 2 2"),
            item("stairs", "1 1 1 1 1 1"),
            item("bathing", "1 1 _ 1 1 1")),  // 기록 5주 → duration 5
            List.of(GRIMACE_3_OF_4, GUARD_3_OF_4), List.of());

        List<Detection> c = ofType(out, DetectionType.STALL_WITH_PAIN);
        assertEquals(2, c.size());
        assertEquals(List.of("ambulation"), c.get(0).items());
        assertEquals(STAND_GRIMACE, c.get(0).signal());
        assertEquals(3, c.get(0).observedWeeks());
        assertEquals(4, c.get(0).windowWeeks());
        assertEquals(STAND_GUARD, c.get(1).signal());
    }

    @Test
    void nonPatternSignalsDoNotFire() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("ambulation", "2 2 2 2 2 2")), List.of(GRIMACE_1_OF_4), List.of());
        assertTrue(ofType(out, DetectionType.STALL_WITH_PAIN).isEmpty());
    }

    @Test
    void riseWithPainPairsPatternWithLongestRiser() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("transfer", "1 1 1 2 2 2"),
            item("toilet", "2 2 3 3 3 3")), List.of(GRIMACE_3_OF_4), List.of());

        List<Detection> c = ofType(out, DetectionType.RISE_WITH_PAIN);
        assertEquals(1, c.size());
        assertEquals(List.of("toilet"), c.get(0).items());  // duration 4 > 3
    }

    @Test
    void declineWithoutAnySignalFiresPerDecliningItem() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("dressing", "3 3 3 2 2 2"),
            item("feeding", "3 3 2 2 2 2")), List.of(), List.of());

        List<Detection> c = ofType(out, DetectionType.DECLINE_NO_SIGNAL);
        assertEquals(2, c.size());
        assertEquals(List.of("dressing"), c.get(0).items());
    }

    @Test
    void declineWithPatternDoesNotFireNoSignalRule() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("dressing", "3 3 3 2 2 2")), List.of(GRIMACE_3_OF_4), List.of());
        assertTrue(ofType(out, DetectionType.DECLINE_NO_SIGNAL).isEmpty());
    }

    @Test
    void fluctuationNeedsTwoReversals() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("grooming", "2 2 3 3 2 3"),   // reversals 2
            item("toilet", "2 2 3 3 2")),      // reversals 1
            List.of(), List.of());

        List<Detection> d = ofType(out, DetectionType.FLUCTUATION);
        assertEquals(1, d.size());
        assertEquals(List.of("grooming"), d.get(0).items());
        assertEquals(6, d.get(0).duration());
    }

    @Test
    void timeOfDayNeedsThreeOfLastFourRecordedWeeks() {
        List<WeeklyNote> notes = List.of(
            new WeeklyNote(1, null),
            new WeeklyNote(2, TimeTag.MORNING),
            new WeeklyNote(3, TimeTag.AFTERNOON),
            new WeeklyNote(4, TimeTag.AFTERNOON),
            new WeeklyNote(5, null),
            new WeeklyNote(6, TimeTag.AFTERNOON));
        List<Detection> out = CrossDetector.detect(SET, List.of(item("toilet", "2 2 2 2 2 2")), List.of(), notes);

        List<Detection> e = ofType(out, DetectionType.TIME_OF_DAY);
        assertEquals(1, e.size());
        assertEquals(TimeTag.AFTERNOON, e.get(0).timeTag());
        assertEquals(3, e.get(0).observedWeeks());
        assertEquals(4, e.get(0).windowWeeks());
        assertTrue(e.get(0).items().isEmpty());
    }

    @Test
    void timeOfDayIgnoresAnyTagAndTwoWeeks() {
        List<WeeklyNote> notes = List.of(
            new WeeklyNote(1, TimeTag.ANY), new WeeklyNote(2, TimeTag.ANY),
            new WeeklyNote(3, TimeTag.ANY), new WeeklyNote(4, TimeTag.AFTERNOON),
            new WeeklyNote(5, TimeTag.AFTERNOON));
        List<Detection> out = CrossDetector.detect(SET, List.of(item("toilet", "2 2 2 2 2")), List.of(), notes);
        assertTrue(ofType(out, DetectionType.TIME_OF_DAY).isEmpty());
    }

    @Test
    void aidChangeWithStableLevel() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("ambulation", "2 2 2 2 2 2", Axis.AID, "1 1 1 1 2 2")), List.of(), List.of());

        List<Detection> f = ofType(out, DetectionType.AID_CHANGE);
        assertEquals(1, f.size());
        assertEquals(List.of("ambulation"), f.get(0).items());
        assertEquals(2, f.get(0).duration());
    }

    @Test
    void aidChangeFiresForEitherDirection() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("ambulation", "2 2 2 2 2 2", Axis.AID, "2 2 2 1 1 1")), List.of(), List.of());
        assertEquals(1, ofType(out, DetectionType.AID_CHANGE).size());
    }

    @Test
    void aidChangeNotWhenLevelAlsoMoved() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("ambulation", "1 1 1 2 2 2", Axis.AID, "1 1 1 1 2 2")), List.of(), List.of());
        assertTrue(ofType(out, DetectionType.AID_CHANGE).isEmpty());
    }

    @Test
    void handDisuseWhenLevelUpAndHandDown() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("feeding", "2 2 2 3 3 3", Axis.HAND, "1 1 1 0 0 0")), List.of(), List.of());

        List<Detection> g = ofType(out, DetectionType.HAND_DISUSE);
        assertEquals(1, g.size());
        assertEquals(List.of("feeding"), g.get(0).items());
        assertEquals(3, g.get(0).duration());
    }

    @Test
    void handDisuseNotWhenHandStable() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("feeding", "2 2 2 3 3 3", Axis.HAND, "1 1 1 1 1 1")), List.of(), List.of());
        assertTrue(ofType(out, DetectionType.HAND_DISUSE).isEmpty());
    }

    @Test
    void consistencyDropWithStableLevel() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("stairs", "1 1 1 1 1 1", Axis.CONSISTENCY, "2 2 1 0 0 0")), List.of(), List.of());

        List<Detection> h = ofType(out, DetectionType.CONSISTENCY_DROP);
        assertEquals(1, h.size());
        assertEquals(List.of("stairs"), h.get(0).items());
        assertEquals(4, h.get(0).duration());
    }

    @Test
    void consistencyRiseDoesNotFire() {
        List<Detection> out = CrossDetector.detect(SET, List.of(
            item("stairs", "1 1 1 1 1 1", Axis.CONSISTENCY, "0 0 1 2 2 2")), List.of(), List.of());
        assertTrue(ofType(out, DetectionType.CONSISTENCY_DROP).isEmpty());
    }
}
