package nextvisit.engine;

/** @param pattern 창 안에서 2주 이상 관찰되어 "패턴으로 발화"하는지. README §6 비언어 신호 판정 */
public record SignalPattern(SignalKey key, int weeksObserved, int windowWeeks, boolean pattern) {}
