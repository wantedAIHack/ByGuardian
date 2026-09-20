package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuestionSynthesisPromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestionSynthesisPrompt prompts = new QuestionSynthesisPrompt(mapper);
    private final SynthesisInput input = new SynthesisInput(
        List.of(new SynthesisInput.Detection("D1", 1, "합성 변화 문장입니다. 어떻게 보시나요?")),
        List.of(
            new SynthesisInput.NoteLine(3, "오후", null, "합성 자유 기록"),
            new SynthesisInput.NoteLine(4, null, "화장실 이용", "합성 항목 메모")));

    @Test
    void userMessageCarriesDetectionsAndNotesAsJsonWithoutNullFields() throws Exception {
        JsonNode user = mapper.readTree(prompts.build(input, Optional.empty()).userMessage());

        assertThat(user.at("/detections/0/id").asText()).isEqualTo("D1");
        assertThat(user.at("/detections/0/sentence").asText()).isEqualTo("합성 변화 문장입니다. 어떻게 보시나요?");
        assertThat(user.at("/notes/0/week").asInt()).isEqualTo(3);
        assertThat(user.at("/notes/0/timeTag").asText()).isEqualTo("오후");
        assertThat(user.at("/notes/0").has("item")).isFalse();
        assertThat(user.at("/notes/1/item").asText()).isEqualTo("화장실 이용");
        assertThat(user.at("/notes/1").has("timeTag")).isFalse();
        assertThat(user.at("/notes/1/text").asText()).isEqualTo("합성 항목 메모");
    }

    @Test
    void systemMessageStatesTheContract() {
        String system = prompts.build(input, Optional.empty()).systemMessage();

        assertThat(system)
            .contains("선생님")
            .contains("입력에 있는 사실만")
            .contains("'~까요?', '~나요?', '~가요?'")
            .contains("1개에서 3개")
            .contains("둘 중 적어도 하나는 비우지 마세요")
            .contains("좋아졌는지 나빠졌는지 판단하지 마세요")
            .contains("'때문', '약', '진통제', '복용'")
            .contains("물음표를 정확히 하나만")
            .contains("'N주' 형태로만")
            .contains("{\"questions\":[{\"sentence\":\"오후마다 어깨를 자주 만지시는데 어떤 점을 살펴보면 좋을까요?\",\"detections\":[\"D1\"],\"noteWeeks\":[3]}]}")
            .doesNotContain("/no_think");
    }

    @Test
    void retryNamesTheViolatedRule() {
        String system = prompts.build(input, Optional.of("UNSUPPORTED_NUMBER")).systemMessage();
        assertThat(system)
            .contains("직전 응답은 검증 규칙 UNSUPPORTED_NUMBER을 위반했습니다.")
            .endsWith("이 규칙을 지켜 다시 만드세요.");
    }

    // 규칙 이름만 돌려주면 모델이 무엇을 고쳐야 할지 알 수 없다. 운영 실측에서
    // qwen3:4b는 "...확인해 주시겠어요?"를 세 번 연속으로 내놓고(temperature 0.1,
    // seed 0이라 재시도가 같은 답을 반복한다) QUESTION_MARK으로 세 번 다 거부돼
    // 보호자가 템플릿 질문만 받았다. 어미를 짚어 주면 같은 입력에서 통과한다.
    @Test
    void retryTellsTheModelWhatToFixNotJustTheRuleName() {
        String system = prompts.build(input, Optional.of("QUESTION_MARK")).systemMessage();
        assertThat(system)
            .contains("'~나요?'")
            .contains("'~까요?'")
            .contains("'~주시겠어요?'");
    }

    @Test
    void everyValidatorRuleHasRetryGuidance() {
        for (SynthesisValidator.Rule rule : SynthesisValidator.Rule.values()) {
            assertThat(QuestionSynthesisPrompt.RETRY_GUIDANCE)
                .as("retry guidance for %s", rule)
                .containsKey(rule.name());
        }
    }

    @Test
    void unknownRuleStillProducesAUsablePrompt() {
        String system = prompts.build(input, Optional.of("NOT_A_RULE")).systemMessage();
        assertThat(system).endsWith("직전 응답은 검증 규칙 NOT_A_RULE을 위반했습니다. 이 규칙을 지켜 다시 만드세요.");
    }
}
