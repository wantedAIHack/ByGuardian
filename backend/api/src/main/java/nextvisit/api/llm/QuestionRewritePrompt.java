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
        그 밖의 글자 추가, 삭제, 동의어 치환, 순서 변경, 새 사실, 판단, 진단, 처방, 치료, 재활, 운동, 위험 평가 또는 행동 지시는 금지입니다.
        항목, 기간, 방향과 모든 아라비아 숫자를 그대로 보존하세요.
        입력과 같은 rank를 정확히 한 번씩 반환하고 다른 rank를 만들지 마세요.
        각 sentence는 1자 이상 200자 이하이며 물음표 하나로 끝나야 합니다.
        마크다운과 설명을 쓰지 말고 정확히 {"questions":[{"rank":1,"sentence":"질문?"}]} 형태의 JSON 객체만 반환하세요.
        """;

    private final ObjectMapper mapper;

    public QuestionRewritePrompt(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LlmPrompt build(List<QuestionCacheBody.Q> questions, Optional<String> retryRule) {
        List<InputQuestion> inputs = questions.stream()
            .map(q -> new InputQuestion(q.rank(), q.templateSentence()))
            .toList();
        String systemMessage = retryRule
            .map(rule -> SYSTEM + "\n직전 응답은 검증 규칙 " + rule + "을 위반했습니다. 이 규칙을 지켜 다시 생성하세요.")
            .orElse(SYSTEM);
        return new LlmPrompt(systemMessage, json(new InputPayload(inputs)));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("question prompt serialization failed", e);
        }
    }

    private record InputPayload(List<InputQuestion> questions) {}

    private record InputQuestion(int rank, String templateSentence) {}
}
