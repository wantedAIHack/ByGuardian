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
}
