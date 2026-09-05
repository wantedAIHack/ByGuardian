package nextvisit.api.questions;

import java.util.List;

/** 설계 2.3. sentence는 화면용, templateSentence는 폴백·프롬프트 기준. api-llm이 sentence/source만 덮어쓴다. */
public record QuestionCacheBody(List<Q> questions, int engineDetectionCount) {

    public record Q(int rank, String type, List<String> items, SignalRef signal,
                    String templateSentence, String sentence, String source) {}

    public record SignalRef(String action, String kind) {}

    public static final String SOURCE_TEMPLATE = "TEMPLATE";
    public static final String SOURCE_LLM = "LLM";
}
