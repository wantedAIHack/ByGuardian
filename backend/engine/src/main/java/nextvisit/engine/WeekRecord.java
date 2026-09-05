package nextvisit.engine;

import java.util.Map;
import java.util.Set;

/**
 * 한 주의 기록. api가 스냅샷 한 행을 이 모양으로 옮기고, CaseInput.fromWeeks가 항목별 시계열로 전치한다.
 *
 * @param values  항목 코드 → 축 → 그 주의 관찰. 관찰의 week는 이 레코드의 week와 같아야 한다
 * @param signals null이면 그 주에 신호를 묻지 않음(비활성). 빈 집합은 물었고 없었음. 창의 분모가 달라진다
 * @param noteTag 자유 기록의 시간대 태그. 없으면 null. 기록된 주마다 WeeklyNote가 하나 생긴다
 */
public record WeekRecord(
    int week,
    Map<String, Map<Axis, Observation>> values,
    Set<SignalKey> signals,
    TimeTag noteTag
) {
    public WeekRecord {
        if (week < 1) {
            throw new IllegalArgumentException("week must be >= 1, was " + week);
        }
        values = Map.copyOf(values);
        for (var item : values.entrySet()) {
            for (var ax : item.getValue().entrySet()) {
                if (ax.getValue().week() != week) {
                    throw new IllegalArgumentException(
                        item.getKey() + "/" + ax.getKey() + " has week " + ax.getValue().week() + " inside week " + week);
                }
            }
        }
        signals = signals == null ? null : Set.copyOf(signals);
    }
}
