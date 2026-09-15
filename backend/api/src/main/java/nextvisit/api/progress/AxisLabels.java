package nextvisit.api.progress;

import java.util.List;
import nextvisit.engine.Axis;

/** 설계 7절 축 라벨. */
public final class AxisLabels {
    public static final List<Axis> ORDER = List.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY, Axis.HAND);

    private AxisLabels() {}

    public static String of(Axis axis) {
        return switch (axis) {
            case LEVEL -> "도움 수준";
            case AID -> "보조 도구";
            case CONSISTENCY -> "이번 주 빈도";
            case HAND -> "마비 쪽 손";
        };
    }

    /**
     * 선택지 위 헤더로 쓸 질문문. 인라인 라벨과 다른 이유는 쓰이는 자리가 다르기
     * 때문이다. of()는 "문턱·계단 · 이번 주 빈도"처럼 항목 뒤에 붙는 명사구여야 하고,
     * 기록·온보딩 화면은 선택지 위에 h3로 홀로 서므로 질문이어야 뜻이 산다.
     *
     * 질문문이 없는 축은 of()로 폴백한다. 축마다 질문을 억지로 지어내면 지금 고치고
     * 있는 어색함을 그대로 반복하게 된다.
     */
    public static String questionOf(Axis axis) {
        return switch (axis) {
            case CONSISTENCY -> "이번 주에 얼마나 자주 그러셨나요?";
            default -> of(axis);
        };
    }
}
