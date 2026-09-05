package nextvisit.engine;

import java.util.Set;

/** 한 주에 관찰된 신호 집합. 기록은 했지만 신호가 없던 주는 빈 집합. */
public record SignalWeek(int week, Set<SignalKey> signals) {
    public SignalWeek {
        signals = Set.copyOf(signals);
    }
}
