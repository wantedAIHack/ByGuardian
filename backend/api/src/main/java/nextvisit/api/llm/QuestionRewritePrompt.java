package nextvisit.api.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import nextvisit.api.questions.QuestionCacheBody;
import org.springframework.stereotype.Component;

@Component
public class QuestionRewritePrompt {

    private static final String SYSTEM = """
        /no_think
        입력 templateSentence를 NFC 정규화한 그대로 반환하거나 다음 두 표면 변환만 사용할 수 있습니다.
        마지막 "습니다. "은 같은 앞부분과 뒤 질문을 그대로 둔 "는데 "로만 바꿀 수 있습니다. 예: "...습니다. 어떻게 ...?" → "...는데 어떻게 ...?"
        마지막 "입니다. "은 같은 앞부분과 뒤 질문을 그대로 둔 "인데 "로만 바꿀 수 있습니다. 예: "...입니다. 어떻게 ...?" → "...인데 어떻게 ...?"
        문장에 마지막 "습니다. " 또는 "입니다. "가 있으면 위 변환을 반드시 한 번 적용하세요.
        그 밖의 글자 추가, 삭제, 동의어 치환, 순서 변경, 새 사실, 판단, 진단, 처방, 치료, 재활, 운동, 위험 평가 또는 행동 지시는 금지입니다.
        항목, 기간, 방향과 모든 아라비아 숫자를 그대로 보존하세요.
        입력과 같은 rank를 정확히 한 번씩 반환하고 다른 rank를 만들지 마세요.
        각 sentence는 1자 이상 200자 이하이며 물음표 하나로 끝나야 합니다.
        마크다운과 설명을 쓰지 말고 정확히 {"questions":[{"rank":1,"sentence":"질문?"}]} 형태의 JSON 객체만 반환하세요.
        """;

    /**
     * 규칙을 글로만 주면 사고를 끈 qwen3는 템플릿을 그대로 되돌려주고, 사고를 켜면 마침표를 남기거나
     * 판단을 반복하다 출력 예산을 다 쓴다(docs/qa/2026-09-17-llm-activation.md 9절).
     * 그래서 허용된 결합 하나를 실제 입출력 한 쌍으로 보여준다. 예시 문장은 엔진 템플릿 형식을 따르되
     * 실제 입력과 겹치지 않는 합성 문장이고, 출력은 검증기를 통과하는 결합형뿐이다.
     */
    private static final List<InputQuestion> EXAMPLE_INPUT = List.of(
        new InputQuestion(1, "목욕은 2주째 그대로입니다. 어떻게 보시나요?"),
        new InputQuestion(2, "앉을 때 어깨를 감싸시는 걸 3주 중 1주 봤습니다. 불편하신 걸까요?"),
        new InputQuestion(3, "세수·양치는 도움 조금으로 바뀌었습니다. 괜찮은 걸까요?"));
    private static final List<OutputQuestion> EXAMPLE_OUTPUT = List.of(
        new OutputQuestion(1, "목욕은 2주째 그대로인데 어떻게 보시나요?"),
        new OutputQuestion(2, "앉을 때 어깨를 감싸시는 걸 3주 중 1주 봤는데 불편하신 걸까요?"),
        new OutputQuestion(3, "세수·양치는 도움 조금으로 바뀌었는데 괜찮은 걸까요?"));

    private final ObjectMapper mapper;
    private final List<Example> examples;

    public QuestionRewritePrompt(ObjectMapper mapper) {
        this.mapper = mapper;
        this.examples = List.of(new Example(
            json(new InputPayload(EXAMPLE_INPUT)), json(new OutputPayload(EXAMPLE_OUTPUT))));
    }

    public Prompt build(List<QuestionCacheBody.Q> questions, Optional<String> retryRule) {
        List<InputQuestion> inputs = questions.stream()
            .map(q -> new InputQuestion(q.rank(), q.templateSentence()))
            .toList();

        String userMessage = json(new InputPayload(inputs));

        String systemMessage = retryRule
            .map(rule -> SYSTEM + "\n직전 응답은 검증 규칙 " + rule + "을 위반했습니다. 이 규칙을 지켜 다시 생성하세요.")
            .orElse(SYSTEM);
        return new Prompt(systemMessage, examples, userMessage);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("question prompt serialization failed", e);
        }
    }

    /** examples는 system 다음, 실제 user 앞에 user/assistant 순서로 보낸다. */
    public record Prompt(String systemMessage, List<Example> examples, String userMessage) {
        public Prompt {
            examples = List.copyOf(examples);
        }

        public Prompt(String systemMessage, String userMessage) {
            this(systemMessage, List.of(), userMessage);
        }
    }

    public record Example(String userMessage, String assistantMessage) {}

    private record InputPayload(List<InputQuestion> questions) {}

    private record InputQuestion(int rank, String templateSentence) {}

    private record OutputPayload(List<OutputQuestion> questions) {}

    private record OutputQuestion(int rank, String sentence) {}
}
