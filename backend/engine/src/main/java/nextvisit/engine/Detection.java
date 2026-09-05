package nextvisit.engine;

import java.util.List;

/**
 * 교차 감지 하나.
 *
 * @param items         관련 항목 코드. (a)(b)는 [X, Y], 나머지는 [X]. (e)는 빈 목록
 * @param duration      선택 우선순위용. 종류마다 의미가 다르며 CrossDetector 주석 참고
 * @param signal        STALL_WITH_PAIN, RISE_WITH_PAIN만
 * @param timeTag       TIME_OF_DAY만
 * @param observedWeeks 신호·시간대 종류만. "4주 중 3주"의 3
 * @param windowWeeks   신호·시간대 종류만. "4주 중 3주"의 4
 */
public record Detection(
    DetectionType type,
    List<String> items,
    int duration,
    SignalKey signal,
    TimeTag timeTag,
    int observedWeeks,
    int windowWeeks
) {
    public Detection {
        items = List.copyOf(items);
    }

    static Detection ofItems(DetectionType type, List<String> items, int duration) {
        return new Detection(type, items, duration, null, null, 0, 0);
    }

    static Detection ofSignal(DetectionType type, String item, int duration, SignalPattern p) {
        return new Detection(type, List.of(item), duration, p.key(), null, p.weeksObserved(), p.windowWeeks());
    }

    static Detection ofTime(TimeTag tag, int observed, int window) {
        return new Detection(DetectionType.TIME_OF_DAY, List.of(), observed, null, tag, observed, window);
    }

    public String x() {
        return items.get(0);
    }

    public String y() {
        return items.get(1);
    }
}
