package nextvisit.api.progress;

import java.util.List;
import nextvisit.engine.Observation;
import nextvisit.engine.Verdict;

public final class VerdictUtil {
    private VerdictUtil() {}

    /** since 주 직전 관찰값. 변화가 없으면 현재값. */
    public static int valueBefore(Verdict v) {
        if (v.since() == null) {
            return v.currentValue();
        }
        List<Observation> t = v.trajectory();
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).week() == v.since()) {
                return i == 0 ? t.get(0).value() : t.get(i - 1).value();
            }
        }
        return v.currentValue();
    }
}
