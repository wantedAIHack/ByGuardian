package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nextvisit.engine.demo.DemoSeed;
import org.junit.jupiter.api.Test;

class WeekRecordTest {

    private static Map<String, Map<Axis, Observation>> values(int week, String code, int level) {
        Map<Axis, Observation> byAxis = new EnumMap<>(Axis.class);
        byAxis.put(Axis.LEVEL, new Observation(week, level, Source.CONFIRMED));
        return Map.of(code, byAxis);
    }

    @Test
    void roundTripPreservesPipelineResult() {
        CaseInput seed = DemoSeed.stroke();
        List<WeekRecord> weeks = seed.toWeeks();
        assertEquals(6, weeks.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), weeks.stream().map(WeekRecord::week).toList());

        PipelineResult direct = Pipeline.run(seed);
        PipelineResult rebuilt = Pipeline.run(CaseInput.fromWeeks(ObservationSet.STROKE, weeks));
        assertEquals(direct.sentences(), rebuilt.sentences());
        assertEquals(direct.detections(), rebuilt.detections());
        assertEquals(direct.patterns(), rebuilt.patterns());
    }

    @Test
    void toWeeksCarriesSourceSignalsAndTags() {
        List<WeekRecord> weeks = DemoSeed.stroke().toWeeks();
        WeekRecord w2 = weeks.get(1);
        assertEquals(Source.CARRIED, w2.values().get("toilet").get(Axis.LEVEL).source());
        assertTrue(w2.signals().contains(new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE)));
        assertNull(w2.noteTag());
        assertEquals(TimeTag.AFTERNOON, weeks.get(2).noteTag());
        assertTrue(weeks.get(0).signals().isEmpty());
    }

    @Test
    void nullSignalsMeansNotAskedSoNoPattern() {
        List<WeekRecord> weeks = List.of(
            new WeekRecord(1, values(1, "toilet", 2), null, null),
            new WeekRecord(2, values(2, "toilet", 2), null, null));
        CaseInput in = CaseInput.fromWeeks(ObservationSet.STROKE, weeks);
        assertTrue(in.signals().isEmpty());
        assertEquals(2, in.notes().size());
        assertTrue(Pipeline.run(in).patterns().isEmpty());
    }

    @Test
    void emptySignalSetCountsAsRecordedWeek() {
        List<WeekRecord> weeks = List.of(
            new WeekRecord(1, values(1, "toilet", 2), Set.of(), null),
            new WeekRecord(2, values(2, "toilet", 2), Set.of(new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE)), null));
        CaseInput in = CaseInput.fromWeeks(ObservationSet.STROKE, weeks);
        assertEquals(2, in.signals().size());
    }

    @Test
    void fromWeeksSortsAndRejectsDuplicates() {
        List<WeekRecord> shuffled = List.of(
            new WeekRecord(3, values(3, "toilet", 3), null, null),
            new WeekRecord(1, values(1, "toilet", 2), null, null),
            new WeekRecord(2, values(2, "toilet", 2), null, null));
        CaseInput in = CaseInput.fromWeeks(ObservationSet.STROKE, shuffled);
        List<Integer> weeksOut = in.series().get("toilet").get(Axis.LEVEL).stream().map(Observation::week).toList();
        assertEquals(List.of(1, 2, 3), weeksOut);

        List<WeekRecord> dup = List.of(
            new WeekRecord(1, values(1, "toilet", 2), null, null),
            new WeekRecord(1, values(1, "toilet", 2), null, null));
        assertThrows(IllegalArgumentException.class, () -> CaseInput.fromWeeks(ObservationSet.STROKE, dup));
    }

    @Test
    void weekRecordRejectsObservationFromAnotherWeek() {
        assertThrows(IllegalArgumentException.class, () -> new WeekRecord(2, values(1, "toilet", 2), null, null));
    }

    @Test
    void axisPresentOnlyInSomeWeeksIsMissingElsewhere() {
        Map<Axis, Observation> w1 = new EnumMap<>(Axis.class);
        w1.put(Axis.LEVEL, new Observation(1, 2, Source.CONFIRMED));
        Map<Axis, Observation> w2 = new EnumMap<>(Axis.class);
        w2.put(Axis.LEVEL, new Observation(2, 2, Source.CONFIRMED));
        w2.put(Axis.AID, new Observation(2, 1, Source.CONFIRMED));
        CaseInput in = CaseInput.fromWeeks(ObservationSet.STROKE, List.of(
            new WeekRecord(1, Map.of("ambulation", w1), null, null),
            new WeekRecord(2, Map.of("ambulation", w2), null, null)));
        assertEquals(1, in.series().get("ambulation").get(Axis.AID).size());
        assertEquals(2, in.series().get("ambulation").get(Axis.LEVEL).size());
    }

    @Test
    void labelsMaxValueIsPublic() {
        assertEquals(3, Labels.maxValue(Axis.LEVEL));
        assertEquals(4, Labels.maxValue(Axis.AID));
        assertEquals(2, Labels.maxValue(Axis.CONSISTENCY));
        assertEquals(2, Labels.maxValue(Axis.HAND));
    }

    @Test
    void toWeeksKeepsWeeksThatHaveOnlySignalsOrNotes() {
        SignalKey grimace = new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE);
        List<WeekRecord> weeks = List.of(
            new WeekRecord(1, values(1, "toilet", 2), Set.of(), null),
            new WeekRecord(2, Map.of(), Set.of(grimace), TimeTag.AFTERNOON),
            new WeekRecord(3, values(3, "toilet", 2), null, null));
        CaseInput in = CaseInput.fromWeeks(ObservationSet.STROKE, weeks);
        assertEquals(3, in.notes().size());
        assertEquals(2, in.signals().size());

        List<WeekRecord> back = in.toWeeks();
        assertEquals(List.of(1, 2, 3), back.stream().map(WeekRecord::week).toList());
        WeekRecord w2 = back.get(1);
        assertTrue(w2.values().isEmpty());
        assertEquals(Set.of(grimace), w2.signals());
        assertEquals(TimeTag.AFTERNOON, w2.noteTag());
        assertNull(back.get(2).signals());

        CaseInput again = CaseInput.fromWeeks(ObservationSet.STROKE, back);
        assertEquals(in.notes(), again.notes());
        assertEquals(in.signals(), again.signals());
    }
}
