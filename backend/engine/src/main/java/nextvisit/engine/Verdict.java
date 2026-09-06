package nextvisit.engine;

import java.util.List;

/**
 * README §6 반환값.
 *
 * @param since      현재 방향의 첫 변화가 관찰된 주. NO_CHANGE면 null
 * @param duration   since부터 현재까지 기록된 주 수. NO_CHANGE면 기록된 전체 주 수
 * @param reversals  방향 전환 횟수. §7 (d)가 쓴다
 * @param trajectory 입력 그대로. 판정과 무관하게 항상 채운다. Layer 3이 쓴다
 */
public record Verdict(
    Status status,
    Direction direction,
    Integer since,
    int duration,
    int reversals,
    List<Observation> trajectory
) {
    public Verdict {
        trajectory = List.copyOf(trajectory);
    }

    public boolean is(Status s, Direction d) {
        return status == s && direction == d;
    }

    public int currentValue() {
        return trajectory.get(trajectory.size() - 1).value();
    }

    /** since 주 직전 관찰값. since가 없으면(변화 없음) 현재값. */
    public int valueBefore() {
        if (since == null) {
            return currentValue();
        }
        for (int i = 0; i < trajectory.size(); i++) {
            if (trajectory.get(i).week() == since) {
                return i == 0 ? trajectory.get(0).value() : trajectory.get(i - 1).value();
            }
        }
        return currentValue();
    }
}
