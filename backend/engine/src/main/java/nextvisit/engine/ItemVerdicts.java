package nextvisit.engine;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** 한 항목의 축별 판정. LEVEL은 반드시 있다. */
public record ItemVerdicts(String code, Map<Axis, Verdict> byAxis) {

    public ItemVerdicts {
        if (!byAxis.containsKey(Axis.LEVEL)) {
            throw new IllegalArgumentException("LEVEL verdict required for " + code);
        }
        byAxis = Map.copyOf(new EnumMap<>(byAxis));
    }

    public Verdict level() {
        return byAxis.get(Axis.LEVEL);
    }

    public Optional<Verdict> axis(Axis axis) {
        return Optional.ofNullable(byAxis.get(axis));
    }
}
