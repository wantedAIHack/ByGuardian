package nextvisit.engine;

import java.util.List;
import java.util.Map;

/**
 * 한 환자의 엔진 입력. api가 DB 스냅샷을 이 형태로 변환한다.
 *
 * @param series 항목 코드 → 축 → 주차별 관찰. 항목마다 LEVEL 필수. 결측 주는 목록에 없다
 */
public record CaseInput(
    ObservationSet set,
    Map<String, Map<Axis, List<Observation>>> series,
    List<SignalWeek> signals,
    List<WeeklyNote> notes
) {
    public CaseInput {
        series = Map.copyOf(series);
        signals = List.copyOf(signals);
        notes = List.copyOf(notes);
        for (var e : series.entrySet()) {
            String code = e.getKey();
            Item item = set.item(code);
            Map<Axis, List<Observation>> byAxis = e.getValue();
            if (!byAxis.containsKey(Axis.LEVEL)) {
                throw new IllegalArgumentException("LEVEL series required for " + code);
            }
            for (Axis axis : byAxis.keySet()) {
                if (!item.axes().contains(axis)) {
                    throw new IllegalArgumentException(
                        "item " + code + " does not declare axis " + axis);
                }
            }
            for (var axisEntry : byAxis.entrySet()) {
                Axis axis = axisEntry.getKey();
                int max = Labels.maxValue(axis);
                for (Observation o : axisEntry.getValue()) {
                    if (o.value() < 0 || o.value() > max) {
                        throw new IllegalArgumentException(
                            "item " + code + " axis " + axis + " week " + o.week()
                                + " value out of range: " + o.value());
                    }
                }
            }
        }
    }

    /** 주차별 기록을 항목별 시계열로 전치한다. 주차 오름차순 정렬, 중복 주차는 거부. */
    public static CaseInput fromWeeks(ObservationSet set, List<WeekRecord> weeks) {
        List<WeekRecord> sorted = new java.util.ArrayList<>(weeks);
        sorted.sort(java.util.Comparator.comparingInt(WeekRecord::week));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).week() == sorted.get(i - 1).week()) {
                throw new IllegalArgumentException("duplicate week: " + sorted.get(i).week());
            }
        }
        Map<String, Map<Axis, List<Observation>>> series = new java.util.LinkedHashMap<>();
        List<SignalWeek> signals = new java.util.ArrayList<>();
        List<WeeklyNote> notes = new java.util.ArrayList<>();
        for (WeekRecord w : sorted) {
            for (var item : w.values().entrySet()) {
                Map<Axis, List<Observation>> byAxis =
                    series.computeIfAbsent(item.getKey(), k -> new java.util.EnumMap<>(Axis.class));
                for (var ax : item.getValue().entrySet()) {
                    byAxis.computeIfAbsent(ax.getKey(), k -> new java.util.ArrayList<>()).add(ax.getValue());
                }
            }
            if (w.signals() != null) {
                signals.add(new SignalWeek(w.week(), w.signals()));
            }
            notes.add(new WeeklyNote(w.week(), w.noteTag()));
        }
        return new CaseInput(set, series, signals, notes);
    }

    /** fromWeeks의 역. 데모 시드를 주차별 스냅샷으로 만들 때 쓴다. */
    public List<WeekRecord> toWeeks() {
        java.util.TreeMap<Integer, Map<String, Map<Axis, Observation>>> byWeek = new java.util.TreeMap<>();
        for (var item : series.entrySet()) {
            for (var ax : item.getValue().entrySet()) {
                for (Observation o : ax.getValue()) {
                    byWeek.computeIfAbsent(o.week(), k -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(item.getKey(), k -> new java.util.EnumMap<>(Axis.class))
                        .put(ax.getKey(), o);
                }
            }
        }
        Map<Integer, java.util.Set<SignalKey>> sig = new java.util.HashMap<>();
        for (SignalWeek s : signals) {
            sig.put(s.week(), s.signals());
        }
        Map<Integer, TimeTag> tags = new java.util.HashMap<>();
        for (WeeklyNote n : notes) {
            tags.put(n.week(), n.tag());
        }
        for (SignalWeek s : signals) {
            byWeek.computeIfAbsent(s.week(), k -> new java.util.LinkedHashMap<>());
        }
        for (WeeklyNote n : notes) {
            byWeek.computeIfAbsent(n.week(), k -> new java.util.LinkedHashMap<>());
        }
        List<WeekRecord> out = new java.util.ArrayList<>();
        for (var e : byWeek.entrySet()) {
            out.add(new WeekRecord(e.getKey(), e.getValue(), sig.get(e.getKey()), tags.get(e.getKey())));
        }
        return List.copyOf(out);
    }
}
