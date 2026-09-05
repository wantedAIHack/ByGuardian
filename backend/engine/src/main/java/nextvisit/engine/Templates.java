package nextvisit.engine;

import java.util.List;
import java.util.Map;

/**
 * 감지 → 한국어 템플릿 문장. README §8 가드레일 3번의 폴백이자 LLM 프롬프트의 기준 문장.
 * 반드시 질문형으로 끝나고 금지 어휘를 쓰지 않는다. README §2.
 */
public final class Templates {

    /** README §8 가드레일 2번. api의 LLM 출력 검증도 이 목록을 쓴다. */
    public static final List<String> FORBIDDEN =
        List.of("개선", "악화", "호전", "위험", "정상", "비정상", "회복", "좋아지", "나빠지");

    private Templates() {}

    public static boolean isSafe(String sentence) {
        if (sentence == null || !sentence.trim().endsWith("?")) {
            return false;
        }
        for (String w : FORBIDDEN) {
            if (sentence.contains(w)) {
                return false;
            }
        }
        return true;
    }

    public static String render(Detection d, ObservationSet set, Map<String, ItemVerdicts> byCode) {
        return switch (d.type()) {
            case RISE_VS_STALL -> {
                String x = set.item(d.x()).phrase();
                String y = set.item(d.y()).phrase();
                String level = Labels.of(Axis.LEVEL, byCode.get(d.x()).level().currentValue());
                yield Josa.eunNeun(x) + " " + Josa.euroRo(level) + " 바뀌셨는데 "
                    + Josa.eunNeun(y) + " " + byCode.get(d.y()).level().duration() + "주째 그대로입니다. "
                    + Josa.eunNeun(y) + " 왜 안 늘고 있을까요?";
            }
            case RISE_VS_DECLINE -> {
                String x = set.item(d.x()).phrase();
                String y = set.item(d.y()).phrase();
                yield Josa.eunNeun(x) + " 도움이 덜 필요해지셨는데 "
                    + Josa.eunNeun(y) + " 도움이 더 필요해지셨습니다. 어떻게 보시나요?";
            }
            case STALL_WITH_PAIN ->
                d.signal().action().phrase() + " " + d.signal().kind().phrase() + " "
                    + d.windowWeeks() + "주 중 " + d.observedWeeks() + "주 봤습니다. 통증일 수 있을까요?";
            case RISE_WITH_PAIN -> {
                String x = set.item(d.x()).phrase();
                yield Josa.eunNeun(x) + " 도움이 덜 필요해지셨는데 "
                    + d.signal().action().phrase() + " 아파하시는 것도 봤습니다. 괜찮은 걸까요?";
            }
            case DECLINE_NO_SIGNAL -> {
                String x = set.item(d.x()).phrase();
                yield Josa.eunNeun(x) + " " + d.duration() + "주째 도움이 더 필요해지셨습니다. 어떻게 보시나요?";
            }
            case FLUCTUATION -> {
                String x = set.item(d.x()).phrase();
                yield Josa.eunNeun(x) + " " + d.duration() + "주 동안 달라졌다 돌아가기를 반복하고 있습니다. 어떻게 보시나요?";
            }
            case TIME_OF_DAY ->
                d.timeTag().phrase() + "에 대한 기록이 " + d.windowWeeks() + "주 중 " + d.observedWeeks()
                    + "주 있습니다. 시간대와 관련이 있을까요?";
            case AID_CHANGE -> {
                String x = set.item(d.x()).phrase();
                ItemVerdicts v = byCode.get(d.x());
                String level = Labels.of(Axis.LEVEL, v.level().currentValue());
                Verdict aid = v.axis(Axis.AID).orElseThrow();
                String before = Labels.of(Axis.AID, valueBefore(aid));
                String after = Labels.of(Axis.AID, aid.currentValue());
                yield Josa.eunNeun(x) + " " + level + " 그대로인데 " + before + "에서 " + Josa.euroRo(after)
                    + " 바뀐 지 " + d.duration() + "주째입니다. 이대로 괜찮을까요?";
            }
            case HAND_DISUSE -> {
                String x = set.item(d.x()).phrase();
                ItemVerdicts v = byCode.get(d.x());
                String level = Labels.of(Axis.LEVEL, v.level().currentValue());
                String hand = Labels.of(Axis.HAND, v.axis(Axis.HAND).orElseThrow().currentValue());
                yield Josa.eunNeun(x) + " " + Josa.euroRo(level) + " 바뀌셨는데 마비된 손은 "
                    + Josa.euroRo(hand) + " 바뀌었습니다. 괜찮은 걸까요?";
            }
            case CONSISTENCY_DROP -> {
                String x = set.item(d.x()).phrase();
                ItemVerdicts v = byCode.get(d.x());
                String level = Labels.of(Axis.LEVEL, v.level().currentValue());
                String c = Labels.of(Axis.CONSISTENCY, v.axis(Axis.CONSISTENCY).orElseThrow().currentValue());
                yield Josa.eunNeun(x) + " " + level + " 그대로인데 요즘은 " + c + "입니다. 어떻게 보시나요?";
            }
        };
    }

    /** since 주 직전 관찰값. since가 없으면(변화 없음) 현재값. */
    private static int valueBefore(Verdict v) {
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
