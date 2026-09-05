package nextvisit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** README §6 비언어 신호 판정. 최근 기록된 4주 중 2주 이상이면 패턴. */
public final class SignalDetector {

    public static final int WINDOW_WEEKS = 4;
    public static final int PATTERN_THRESHOLD = 2;

    private SignalDetector() {}

    public static List<SignalPattern> judge(List<SignalWeek> weeks) {
        List<SignalWeek> sorted = new ArrayList<>(weeks);
        sorted.sort(Comparator.comparingInt(SignalWeek::week));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).week() == sorted.get(i - 1).week()) {
                throw new IllegalArgumentException("duplicate week: " + sorted.get(i).week());
            }
        }

        int from = Math.max(0, sorted.size() - WINDOW_WEEKS);
        List<SignalWeek> window = sorted.subList(from, sorted.size());

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
