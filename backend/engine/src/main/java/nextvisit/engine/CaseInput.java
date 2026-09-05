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
            set.item(e.getKey());
            if (!e.getValue().containsKey(Axis.LEVEL)) {
                throw new IllegalArgumentException("LEVEL series required for " + e.getKey());
            }
        }
    }
}
