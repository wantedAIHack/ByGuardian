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

    /**
     * 2026-09-17 운영 재현: 규칙을 글로만 주면 qwen3는 사고 과정에서 "입니다."를 "인데"로 바꾸고
     * 마침표를 남기거나("그대로인데. 집 안에서"), 그 판단을 반복하다 max_tokens를 다 써서 빈 content를 냈다.
     * 사고를 끄면 템플릿을 그대로 되돌려줬다. 허용된 결합을 실제로 보여주는 예시 한 쌍이 있어야
     * 사고 없이도 결합을 정확히 적용했다(docs/qa/2026-09-17-llm-activation.md 9절).
     */
    @Test
    void demonstratesTheEnumeratedJoinWithTheSentenceBoundaryRemoved() throws Exception {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "TYPE", List.of(), null, "식사는 4주째 그대로입니다. 어떻게 보시나요?",
            "식사는 4주째 그대로입니다. 어떻게 보시나요?", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt prompt = promptBuilder.build(List.of(input), Optional.empty());

        assertThat(prompt.examples()).hasSize(1);
        QuestionRewritePrompt.Example example = prompt.examples().get(0);
        List<QuestionCacheBody.Q> exampleTemplates = new java.util.ArrayList<>();
        for (JsonNode q : mapper.readTree(example.userMessage()).get("questions")) {
            Set<String> fields = new HashSet<>();
            q.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("rank", "templateSentence");
            String template = q.get("templateSentence").asText();
            exampleTemplates.add(new QuestionCacheBody.Q(q.get("rank").asInt(), "TYPE", List.of(), null,
                template, template, QuestionCacheBody.SOURCE_TEMPLATE));
        }
        assertThat(exampleTemplates).extracting(QuestionCacheBody.Q::templateSentence)
            .allSatisfy(template -> assertThat(template).containsAnyOf("습니다. ", "입니다. "))
            .doesNotContain(input.templateSentence());

        QuestionOutputGuard.Accepted accepted = new QuestionOutputGuard(mapper)
            .validate(exampleTemplates, example.assistantMessage());

        assertThat(accepted.rewrites()).hasSameSizeAs(exampleTemplates);
        for (int i = 0; i < exampleTemplates.size(); i++) {
            String sentence = accepted.rewrites().get(i).sentence();
            assertThat(sentence).isNotEqualTo(exampleTemplates.get(i).templateSentence());
            assertThat(sentence).doesNotContain(". ").containsAnyOf("는데 ", "인데 ");
        }
    }

    @Test
    void retryKeepsTheSameDemonstration() {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "TYPE", List.of(), null, "3주째 그대로입니다. 괜찮을까요?",
            "3주째 그대로입니다. 괜찮을까요?", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt first = promptBuilder.build(List.of(input), Optional.empty());
        QuestionRewritePrompt.Prompt retry = promptBuilder.build(
            List.of(input), Optional.of("SURFACE_REWRITE"));

        assertThat(retry.examples()).isEqualTo(first.examples());
    }

    @Test
    void systemPromptRequiresTheJoinWhenTheBoundaryExists() {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "TYPE", List.of(), null, "식사는 4주째 그대로입니다. 어떻게 보시나요?",
            "식사는 4주째 그대로입니다. 어떻게 보시나요?", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt prompt = promptBuilder.build(List.of(input), Optional.empty());

        assertThat(prompt.systemMessage())
            .contains("마지막 \"습니다. \" 또는 \"입니다. \"가 있으면 위 변환을 반드시 한 번 적용하세요.");
    }
}
