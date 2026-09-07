package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;
import nextvisit.api.questions.QuestionCacheBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuestionOutputGuardTest {

    private final QuestionOutputGuard guard = new QuestionOutputGuard(new ObjectMapper());

    @Test
    void acceptsExactRanksAndRestoresInputOrder() {
        List<QuestionCacheBody.Q> input = List.of(
            question(1, "걷기를 3주 중 2주 지켜봤습니다. 어떻게 보시나요?"),
            question(2, "식사는 4주째 그대로입니다. 어떻게 보시나요?"));
        String content = """
            {"questions":[
              {"rank":2,"sentence":" 식사는 4주째 그대로인데 어떻게 보시나요? "},
              {"rank":1,"sentence":"걷기를 3주 중 2주 지켜봤는데 어떻게 보시나요?"}
            ]}
            """;

        QuestionOutputGuard.Accepted accepted = guard.validate(input, content);

        assertThat(accepted.rewrites()).containsExactly(
            new QuestionOutputGuard.Rewrite(1, "걷기를 3주 중 2주 지켜봤는데 어떻게 보시나요?"),
            new QuestionOutputGuard.Rewrite(2, "식사는 4주째 그대로인데 어떻게 보시나요?"));
    }

    @Test
    void stripsEmSpaceAtSentenceBoundaries() {
        List<QuestionCacheBody.Q> input = List.of(
            question(1, "걷기를 3주 중 2주 지켜봤습니다. 어떻게 보시나요?"));
        String content = """
            {"questions":[
              {"rank":1,"sentence":" 걷기를 3주 중 2주 지켜봤는데 어떻게 보시나요? "}
            ]}
            """;

        QuestionOutputGuard.Accepted accepted = guard.validate(input, content);

        assertThat(accepted.rewrites()).containsExactly(
            new QuestionOutputGuard.Rewrite(1, "걷기를 3주 중 2주 지켜봤는데 어떻게 보시나요?"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidResponses")
    void rejectsTheWholeBatch(String name, String content, QuestionOutputGuard.Rule rule) {
        List<QuestionCacheBody.Q> input = List.of(
            question(1, "걷기를 3주 중 2주 지켜봤습니다. 어떻게 보시나요?"),
            question(2, "식사는 4주째 그대로입니다. 어떻게 보시나요?"));

        QuestionOutputGuard.Rejected rejected = assertThrows(
            QuestionOutputGuard.Rejected.class, () -> guard.validate(input, content));

        assertThat(rejected.rule()).isEqualTo(rule);
        assertThat(rejected.getMessage()).isEqualTo(rule.name());
    }

    static Stream<Arguments> invalidResponses() {
        String longSentence = "가".repeat(201) + "?";
        String nfdForbidden = Normalizer.normalize("재활", Normalizer.Form.NFD);
        return Stream.of(
            Arguments.of("trailing json", "{\"questions\":[]} {\"extra\":1}",
                QuestionOutputGuard.Rule.JSON_OBJECT),
            Arguments.of("duplicate root field", "{\"questions\":[],\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.JSON_OBJECT),
            Arguments.of("duplicate question field", "{\"questions\":[{\"rank\":9,\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.JSON_OBJECT),
            Arguments.of("extra root field", "{\"questions\":[],\"extra\":1}",
                QuestionOutputGuard.Rule.ROOT_FIELDS),
            Arguments.of("wrong count", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_COUNT),
            Arguments.of("extra question field", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\",\"why\":\"x\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_FIELDS),
            Arguments.of("rank overflows int", "{\"questions\":[{\"rank\":4294967297,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_FIELDS),
            Arguments.of("duplicate rank", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":1,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.RANK_SET),
            Arguments.of("multiple question marks", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요??\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_MARK),
            Arguments.of("too long", "{\"questions\":[{\"rank\":1,\"sentence\":\"" + longSentence + "\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.SENTENCE_LENGTH),
            Arguments.of("markdown", "{\"questions\":[{\"rank\":1,\"sentence\":\"**3주** 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("lone backtick", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주`인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("single underscore emphasis", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 _그대로_인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("fenced code", "{\"questions\":[{\"rank\":1,\"sentence\":\"```3주 중 2주```인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("list prefix", "{\"questions\":[{\"rank\":1,\"sentence\":\"- 3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("blockquote", "{\"questions\":[{\"rank\":1,\"sentence\":\"> 3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("blockquote without whitespace", "{\"questions\":[{\"rank\":1,\"sentence\":\">3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("horizontal rule", "{\"questions\":[{\"rank\":1,\"sentence\":\"---\\n3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("parenthesized ordered list", "{\"questions\":[{\"rank\":1,\"sentence\":\"1) 3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("autolink", "{\"questions\":[{\"rank\":1,\"sentence\":\"<https://example.com> 3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("raw html", "{\"questions\":[{\"rank\":1,\"sentence\":\"<em>3주 중 2주인가요</em>?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("nfd forbidden word", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 " + nfdForbidden + "인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.FORBIDDEN_WORD),
            Arguments.of("directive", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 확인하세요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("plain imperative hara", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 기록을 삭제하라?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("plain imperative eora", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 동안 먹어라?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("polite request baramnida", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 확인하기 바랍니다?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("suggestive action bwa-yo", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 확인해 봐요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("informal imperative", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 기록을 지워?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("obligation framed as question", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 기록을 삭제해야 할까요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("changed numbers", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 1주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS),
            Arguments.of("omitted number", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 동안인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS),
            Arguments.of("added number", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주와 5일인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS),
            Arguments.of("duplicated number", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주, 다시 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS));
    }

    private static QuestionCacheBody.Q question(int rank, String template) {
        return new QuestionCacheBody.Q(rank, "TYPE", List.of(), null,
            template, template, QuestionCacheBody.SOURCE_TEMPLATE);
    }
}
