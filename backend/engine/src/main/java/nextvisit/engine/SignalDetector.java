package nextvisit.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** README §6 비언어 신호 판정. 최근 기록된 4주 중 2주 이상이면 패턴. */
public final class SignalDetector {

    public static final int WINDOW_WEEKS = 4;
    public static final int PATTERN_THRESHOLD = 2;

    private SignalDetector() {}

    public static List<SignalPattern> judge(List<SignalWeek> weeks) {
        int from = Math.max(0, weeks.size() - WINDOW_WEEKS);
        List<SignalWeek> window = weeks.subList(from, weeks.size());

        TreeMap<SignalKey, Integer> counts = new TreeMap<>();
        for (SignalWeek w : window) {
            for (SignalKey k : w.signals()) {
                counts.merge(k, 1, Integer::sum);
            }
        }

        List<SignalPattern> out = new ArrayList<>();
        counts.forEach((key, n) ->
            out.add(new SignalPattern(key, n, window.size(), n >= PATTERN_THRESHOLD)));
        return List.copyOf(out);
    }
}
