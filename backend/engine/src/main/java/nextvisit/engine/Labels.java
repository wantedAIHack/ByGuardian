package nextvisit.engine;

import java.util.List;
import java.util.Map;

/** 축 값 → 표시 문구. README §5 표 그대로. 값은 0부터. */
public final class Labels {

    private static final Map<Axis, List<String>> TABLE = Map.of(
        Axis.LEVEL,       List.of("대부분 도움", "손 잡아드림", "지켜보면 됨", "혼자 하심"),
        Axis.AID,         List.of("휠체어", "워커", "지팡이", "가구·난간 잡음", "아무것도 안 잡음"),
        Axis.CONSISTENCY, List.of("좋은 날만", "대체로", "매번"),
        Axis.HAND,        List.of("안 씀", "거들기만", "주로 씀")
    );

    private Labels() {}

    public static String of(Axis axis, int value) {
        List<String> labels = TABLE.get(axis);
        if (value < 0 || value >= labels.size()) {
            throw new IllegalArgumentException(axis + " value out of range: " + value);
        }
        return labels.get(value);
    }

    /** 해당 축의 합법적인 최댓값. 최솟값은 항상 0. */
    public static int maxValue(Axis axis) {
        return TABLE.get(axis).size() - 1;
    }
}
