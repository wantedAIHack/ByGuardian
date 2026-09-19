package nextvisit.api.questions;

import java.util.List;

/**
 * 설계 2.3 + 2026-09-17 정리 설계 4.1. sentence는 화면용, templateSentence는 폴백 기준.
 * origin·basis는 2026-09-17에 추가돼 그 전 캐시에는 없다 — originOrDefault/basisOrEmpty로 읽는다.
 */
public record QuestionCacheBody(List<Q> questions, int engineDetectionCount) {

    public static final String SOURCE_TEMPLATE = "TEMPLATE";
    public static final String SOURCE_LLM = "LLM";
    public static final String ORIGIN_TEMPLATE = "TEMPLATE";
    public static final String ORIGIN_LLM = "LLM";
    public static final String ORIGIN_CAREGIVER = "CAREGIVER";
    public static final String TYPE_SYNTHESIS = "SYNTHESIS";

    public record Q(int rank, String type, List<String> items, SignalRef signal,
                    String templateSentence, String sentence, String source,
                    String origin, Basis basis) {

        public static Q template(int rank, String type, List<String> items, SignalRef signal, String sentence) {
            return new Q(rank, type, items, signal, sentence, sentence, SOURCE_TEMPLATE, ORIGIN_TEMPLATE,
                new Basis(List.of("D" + rank), List.of()));
        }

        public String originOrDefault() {
            if (origin != null) {
                return origin;
            }
            return SOURCE_LLM.equals(source) ? ORIGIN_LLM : ORIGIN_TEMPLATE;
        }

        public Basis basisOrEmpty() {
            return basis != null ? basis : Basis.EMPTY;
        }
    }

    public record Basis(List<String> detections, List<Integer> noteWeeks) {
        public static final Basis EMPTY = new Basis(List.of(), List.of());
    }

    public record SignalRef(String action, String kind) {}
}
