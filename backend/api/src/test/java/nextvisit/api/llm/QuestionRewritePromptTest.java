package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import nextvisit.api.questions.QuestionCacheBody;
import org.junit.jupiter.api.Test;

class QuestionRewritePromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestionRewritePrompt promptBuilder = new QuestionRewritePrompt(mapper);

    @Test
    void sendsOnlyRankAndTemplateSentence() throws Exception {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "FREE_NOTE_SENTINEL", List.of("SECRET_ITEM"),
            new QuestionCacheBody.SignalRef("SECRET_ACTION", "SECRET_KIND"),
            "걷기는 3주째 그대로입니다. 어떻게 보시나요?",
            "OUTPUT_SENTINEL", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt prompt = promptBuilder.build(List.of(input), Optional.empty());
        JsonNode root = mapper.readTree(prompt.userMessage());
        JsonNode question = root.get("questions").get(0);
        Set<String> fields = new HashSet<>();
        question.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder("rank", "templateSentence");
        assertThat(question.get("rank").asInt()).isEqualTo(1);
        assertThat(question.get("templateSentence").asText())
            .isEqualTo("걷기는 3주째 그대로입니다. 어떻게 보시나요?");
        assertThat(prompt.userMessage()).doesNotContain(
            "FREE_NOTE_SENTINEL",
            "SECRET_ITEM",
            "SECRET_ACTION",
            "SECRET_KIND",
            "OUTPUT_SENTINEL");
        assertThat(prompt.systemMessage()).contains("/no_think");
    }

    @Test
    void retryAddsOnlyTheRuleNameAndKeepsTheSameUserJson() {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "TYPE", List.of(), null, "3주째 그대로인가요?",
            "3주째 그대로인가요?", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt first = promptBuilder.build(List.of(input), Optional.empty());
        QuestionRewritePrompt.Prompt retry = promptBuilder.build(
            List.of(input), Optional.of("NUMBER_TOKENS"));

        assertThat(retry.userMessage()).isEqualTo(first.userMessage());
        assertThat(retry.systemMessage()).contains("NUMBER_TOKENS");
        assertThat(retry.systemMessage()).doesNotContain("거부된 문장");
    }

    @Test
    void systemPromptPermitsOnlyTheFiniteSurfaceRewriteBoundary() {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "TYPE", List.of(), null, "식사는 4주째 그대로입니다. 어떻게 보시나요?",
            "식사는 4주째 그대로입니다. 어떻게 보시나요?", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt prompt = promptBuilder.build(List.of(input), Optional.empty());

        assertThat(prompt.systemMessage()).contains(
            "그대로 반환하거나 다음 두 표면 변환만 사용할 수 있습니다.",
            "습니다. 어떻게",
            "는데 어떻게",
            "입니다. 어떻게",
            "인데 어떻게",
            "그 밖의 글자 추가, 삭제, 동의어 치환");
        assertThat(prompt.systemMessage()).doesNotContain("자연스러운 한국어 질문으로만 다듬습니다.");
    }
}
