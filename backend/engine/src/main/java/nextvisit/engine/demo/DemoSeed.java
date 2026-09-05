package nextvisit.engine.demo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nextvisit.engine.Axis;
import nextvisit.engine.CaseInput;
import nextvisit.engine.Observation;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.SignalAction;
import nextvisit.engine.SignalKey;
import nextvisit.engine.SignalKind;
import nextvisit.engine.SignalWeek;
import nextvisit.engine.Source;
import nextvisit.engine.TimeTag;
import nextvisit.engine.WeeklyNote;

/** README §10 데모 시드. 오른쪽 마비, 표현이 어려운 어르신, 작성자는 딸. 2주차는 "달라진 것 없음" 탭. */
public final class DemoSeed {

    public static final int WEEKS = 6;
    /** 2주차는 "달라진 것 없음"으로 전부 carried. */
    public static final int CARRIED_WEEK = 2;

    private DemoSeed() {}

    public static CaseInput stroke() {
        Map<String, Map<Axis, List<Observation>>> series = new LinkedHashMap<>();
        put(series, "toilet",     Axis.LEVEL,       2, 2, 3, 3, 3, 3);
        put(series, "ambulation", Axis.LEVEL,       2, 2, 2, 2, 2, 2);
        put(series, "ambulation", Axis.AID,         1, 1, 1, 1, 2, 2);
        put(series, "grooming",   Axis.LEVEL,       2, 2, 3, 3, 2, 3);
        put(series, "grooming",   Axis.HAND,        1, 1, 1, 1, 1, 1);
        put(series, "transfer",   Axis.LEVEL,       1, 1, 1, 2, 2, 2);
        put(series, "feeding",    Axis.LEVEL,       2, 2, 2, 3, 3, 3);
        put(series, "feeding",    Axis.HAND,        1, 1, 1, 0, 0, 0);
        put(series, "dressing",   Axis.LEVEL,       3, 3, 3, 2, 2, 2);
        put(series, "stairs",     Axis.LEVEL,       1, 1, 1, 1, 1, 1);
        put(series, "stairs",     Axis.CONSISTENCY, 2, 2, 1, 0, 0, 0);
        put(series, "bathing",    Axis.LEVEL,       1, 1, 1, 1, 1, 1);

        SignalKey grimace = new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE);
        SignalKey guarding = new SignalKey(SignalAction.STANDING, SignalKind.GUARDING);
        List<SignalWeek> signals = List.of(
            new SignalWeek(1, Set.of()),
            new SignalWeek(2, Set.of(grimace)),
            new SignalWeek(3, Set.of(grimace, guarding)),
            new SignalWeek(4, Set.of()),
            new SignalWeek(5, Set.of(grimace, guarding)),
            new SignalWeek(6, Set.of(grimace, guarding)));

        List<WeeklyNote> notes = List.of(
            new WeeklyNote(1, null),
            new WeeklyNote(2, null),
            new WeeklyNote(3, TimeTag.AFTERNOON),
            new WeeklyNote(4, TimeTag.AFTERNOON),
            new WeeklyNote(5, null),
            new WeeklyNote(6, TimeTag.AFTERNOON));

        return new CaseInput(ObservationSet.STROKE, series, signals, notes);
    }

    private static void put(Map<String, Map<Axis, List<Observation>>> series, String code, Axis axis, int... values) {
        if (values.length != WEEKS) {
            throw new IllegalArgumentException(code + "/" + axis + " needs " + WEEKS + " values");
        }
        List<Observation> t = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            int week = i + 1;
            Source source = week == CARRIED_WEEK ? Source.CARRIED : Source.CONFIRMED;
            t.add(new Observation(week, values[i], source));
        }
        series.computeIfAbsent(code, k -> new EnumMap<>(Axis.class)).put(axis, List.copyOf(t));
    }
}
