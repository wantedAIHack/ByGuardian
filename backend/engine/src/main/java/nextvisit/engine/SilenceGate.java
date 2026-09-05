package nextvisit.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 침묵 게이트. README §6. 순수 함수. LLM 금지.
 *
 * 규칙 (위에서부터):
 *  1. 변화가 하나도 없음               → NO_CHANGE
 *  2. 현재 값 유지 주 수가 4 이상       → SUSTAINED  (그 전에 흔들렸어도 회복으로 봄)
 *  3. 궤적에 방향 전환이 하나라도 있음   → FLUCTUATING
 *  4. 현재 값 유지 주 수가 1            → OBSERVED_ONCE
 *  5. 그 외 (유지 2~3주)               → SUSTAINED
 */
public final class SilenceGate {

    /** 이 주 수 이상 현재 값이 유지되면 이전 흔들림과 무관하게 sustained. */
    public static final int RECOVERY_HOLD_WEEKS = 4;

    private SilenceGate() {}

    private record Change(int index, Direction direction) {}

    public static Verdict judge(List<Observation> trajectory) {
        if (trajectory == null || trajectory.isEmpty()) {
            throw new IllegalArgumentException("trajectory must not be empty");
        }
        List<Observation> t = List.copyOf(trajectory);
        for (int i = 1; i < t.size(); i++) {
            if (t.get(i).week() <= t.get(i - 1).week()) {
                throw new IllegalArgumentException("weeks must be strictly increasing at index " + i);
            }
        }

        List<Change> changes = new ArrayList<>();
        for (int i = 1; i < t.size(); i++) {
            int delta = t.get(i).value() - t.get(i - 1).value();
            if (delta != 0) {
                changes.add(new Change(i, delta > 0 ? Direction.UP : Direction.DOWN));
            }
        }

        if (changes.isEmpty()) {
            return new Verdict(Status.NO_CHANGE, null, null, t.size(), 0, t);
        }

        int reversals = 0;
        for (int i = 1; i < changes.size(); i++) {
            if (changes.get(i).direction() != changes.get(i - 1).direction()) {
                reversals++;
            }
        }

        Change last = changes.get(changes.size() - 1);
        int hold = t.size() - last.index();

        int runStart = changes.size() - 1;
        while (runStart > 0 && changes.get(runStart - 1).direction() == last.direction()) {
            runStart--;
        }
        int sinceIndex = changes.get(runStart).index();
        int since = t.get(sinceIndex).week();
        int duration = t.size() - sinceIndex;

        Status status;
        if (hold >= RECOVERY_HOLD_WEEKS) {
            status = Status.SUSTAINED;
        } else if (reversals > 0) {
            status = Status.FLUCTUATING;
        } else if (hold == 1) {
            status = Status.OBSERVED_ONCE;
        } else {
            status = Status.SUSTAINED;
        }
        return new Verdict(status, last.direction(), since, duration, reversals, t);
    }
}
