package nextvisit.engine;

import java.util.Set;

/**
 * 관찰 항목. README §5.
 *
 * @param label  화면 표시 문구 ("침대·의자에서 옮겨 앉기")
 * @param phrase 문장 안에 들어가는 짧은 구 ("옮겨 앉기")
 */
public record Item(String code, String label, String phrase, String group, Set<Axis> axes) {
    public Item {
        axes = Set.copyOf(axes);
        if (!axes.contains(Axis.LEVEL)) {
            throw new IllegalArgumentException("every item has LEVEL: " + code);
        }
    }
}
