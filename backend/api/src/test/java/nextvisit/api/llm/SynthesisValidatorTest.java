package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class SynthesisValidatorTest {

    private final SynthesisValidator validator = new SynthesisValidator(new ObjectMapper());

    private final SynthesisInput input = new SynthesisInput(
        List.of(new SynthesisInput.Detection("D1", 1,
            "화장실은 혼자 가시게 바뀌셨는데 집 안에서 걷기는 6주째 그대로입니다. 걷기는 왜 안 늘고 있을까요?")),
        List.of(
            new SynthesisInput.NoteLine(3, "오후", null, "합성: 오후만 되면 오른쪽 어깨를 자꾸 만지신다"),
            new SynthesisInput.NoteLine(5, null, "화장실 이용", "합성: 밤에 두 번 깨서 화장실에 가셨다 7시쯤")));

    private static String one(String sentence, String detections, String weeks) {
        return "{\"questions\":[{\"sentence\":\"" + sentence + "\",\"detections\":" + detections
            + ",\"noteWeeks\":" + weeks + "}]}";
    }

    @Test
    void acceptsGroundedQuestionsAndNormalizesSentences() {
        String content = "{\"questions\":["
            + "{\"sentence\":\"  오후마다 오른쪽 어깨를 자꾸 만지시는데 어떤 점을 살펴보면 좋을까요?  \",\"detections\":[],\"noteWeeks\":[3]},"
            + "{\"sentence\":\"걷기가 6주째 그대로인데 집에서 어떻게 해야 할까요?\",\"detections\":[\"D1\"],\"noteWeeks\":[]}"
            + "]}";

        SynthesisValidator.Accepted accepted = validator.validate(input, content);

        assertThat(accepted.questions()).containsExactly(
            new SynthesisValidator.Question("오후마다 오른쪽 어깨를 자꾸 만지시는데 어떤 점을 살펴보면 좋을까요?", List.of(), List.of(3)),
            new SynthesisValidator.Question("걷기가 6주째 그대로인데 집에서 어떻게 해야 할까요?", List.of("D1"), List.of()));
    }

    @Test
    void allowsTheCitedWeekNumberAndNumbersFromCitedNotes() {
        String content = one("5주에 적은 것처럼 밤 7시쯤 화장실에 가시는데 괜찮을까요?", "[]", "[5]");
        assertThat(validator.validate(input, content).questions()).hasSize(1);
    }

    @Test
    void allowsInnocentWordsThatContainStandaloneYakSubstring() {
        String content = one("오후마다 어깨가 약간 결리시는데 어떤 점을 보면 좋을까요?", "[]", "[3]");
        assertThat(validator.validate(input, content).questions()).hasSize(1);
    }

    static Stream<Arguments> rejected() {
        String ok = "오후마다 어깨를 자꾸 만지시는데 어떤 점을 보면 좋을까요?";
        return Stream.of(
            Arguments.of("not json", SynthesisValidator.Rule.JSON_OBJECT),
            Arguments.of("[1]", SynthesisValidator.Rule.JSON_OBJECT),
            Arguments.of("{\"questions\":[],\"note\":1}", SynthesisValidator.Rule.ROOT_FIELDS),
            Arguments.of("{\"questions\":{}}", SynthesisValidator.Rule.QUESTIONS_ARRAY),
            Arguments.of("{\"questions\":[]}", SynthesisValidator.Rule.QUESTION_COUNT),
            Arguments.of("{\"questions\":[" + String.join(",", List.of(
                "{\"sentence\":\"" + ok + "\",\"detections\":[],\"noteWeeks\":[3]}",
                "{\"sentence\":\"" + ok + " 하나\",\"detections\":[],\"noteWeeks\":[3]}",
                "{\"sentence\":\"" + ok + " 둘\",\"detections\":[],\"noteWeeks\":[3]}",
                "{\"sentence\":\"" + ok + " 셋\",\"detections\":[],\"noteWeeks\":[3]}")) + "]}",
                SynthesisValidator.Rule.QUESTION_COUNT),
            Arguments.of("{\"questions\":[{\"sentence\":\"" + ok + "\",\"noteWeeks\":[3]}]}", SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of("{\"questions\":[{\"sentence\":\"" + ok + "\",\"detections\":\"D1\",\"noteWeeks\":[3]}]}", SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of("{\"questions\":[{\"sentence\":\"" + ok + "\",\"detections\":[],\"noteWeeks\":[\"3\"]}]}", SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of(one("괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.SENTENCE_LENGTH),
            Arguments.of(one("가".repeat(158) + "까요?", "[]", "[3]"), SynthesisValidator.Rule.SENTENCE_LENGTH),
            Arguments.of(one("**오후마다** 어깨를 만지시는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.MARKDOWN),
            Arguments.of(one("오후마다 어깨를 자꾸 만지시는 것을 말씀드립니다.", "[]", "[3]"), SynthesisValidator.Rule.QUESTION_MARK),
            Arguments.of(one("운동을 더 하면 어깨가 괜찮아질까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("밤에 깨시면 같이 가면 돼요 그렇게 하면 될까요?", "[]", "[5]"), SynthesisValidator.Rule.DIRECTIVE),
            Arguments.of(one(ok, "[]", "[]"), SynthesisValidator.Rule.BASIS_EMPTY),
            Arguments.of(one(ok, "[\"D9\"]", "[]"), SynthesisValidator.Rule.UNKNOWN_DETECTION),
            Arguments.of(one(ok, "[]", "[2]"), SynthesisValidator.Rule.UNKNOWN_NOTE_WEEK),
            Arguments.of(one("4주 중 3주나 어깨를 만지셨는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            Arguments.of(one("걷기가 8주째 그대로인데 괜찮을까요?", "[\"D1\"]", "[]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            Arguments.of("{\"questions\":["
                + "{\"sentence\":\"" + ok + "\",\"detections\":[],\"noteWeeks\":[3]},"
                + "{\"sentence\":\" " + ok + "\",\"detections\":[\"D1\"],\"noteWeeks\":[]}]}",
                SynthesisValidator.Rule.DUPLICATE),
            // Fix round 1, item 1: non-ASCII decimal digits must not slip past the ASCII-only number check.
            Arguments.of(one("어깨를 ３주째 만지시는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            Arguments.of(one("어깨를 ９번씩 만지시는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            Arguments.of(one("어깨를 ٩번 만지시는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            // Fix round 1, item 2: improvement/decline judgments the fixed FORBIDDEN list misses (ㄹ-irregular conjugations).
            Arguments.of(one("걷기가 나빠진 것 같은데 어떻게 보시나요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("어깨는 조금 나아진 것 같은데 어떻게 보시나요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("걷기가 앞으로 좋아질지 어떻게 보시나요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            // Fix round 1, item 3: a directive/statement clause before the question mark.
            Arguments.of(one("걷기 연습을 매일 해야 합니다. 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.QUESTION_MARK),
            Arguments.of(one("어깨를 자주 주물러 드려야 합니다. 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.QUESTION_MARK),
            Arguments.of(one("어깨가 아프신가요? 약을 드려야 할까요?", "[]", "[3]"), SynthesisValidator.Rule.QUESTION_MARK),
            // Fix round 1, item 4: a mid-word space or invisible format character must not defeat the forbidden-word scan.
            Arguments.of(one("치 료를 받아야 할까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("진​단을 받아야 할까요?", "[]", "[3]"), SynthesisValidator.Rule.MARKDOWN),
            // Fix round 1, item 5: a cited week number reused as an unrelated count (not followed by 주).
            Arguments.of(one("어깨를 3번이나 만지시는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            // Fix round 1, item 6: duplicate ids/weeks, and line/paragraph separators as markdown-equivalent control characters.
            Arguments.of(one(ok, "[\"D1\",\"D1\"]", "[]"), SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of(one(ok, "[]", "[3,3]"), SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of(one("오후마다 어깨를 자꾸 만지시는데 어떤 점을 보면 좋을까요?", "[]", "[3]"), SynthesisValidator.Rule.MARKDOWN),
            Arguments.of(one("오후마다 어깨를 자꾸 만지시는데 어떤 점을 보면 좋을까요?", "[]", "[3]"), SynthesisValidator.Rule.MARKDOWN),
            // Fix round 2: item 2's 때문/진통제/복용/투약/standalone-약 branches each need their own
            // single-clause proof — the original example had two '?' and was caught by QUESTION_MARK first.
            Arguments.of(one("어깨를 만지시는 건 굳은살 때문일까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("진통제를 드려도 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("복용 중인 것을 여쭤봐도 될까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("투약 시간을 여쭤봐도 될까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("약을 드리는 게 나을지 여쭤봐도 될까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD));
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejectsWithTheStableRule(String content, SynthesisValidator.Rule rule) {
        assertThatThrownBy(() -> validator.validate(input, content))
            .isInstanceOfSatisfying(SynthesisValidator.Rejected.class, e -> assertThat(e.rule()).isEqualTo(rule));
    }

    @Test
    void rejectsDuplicateJsonKeysAndTrailingTokens() {
        assertThatThrownBy(() -> validator.validate(input,
            "{\"questions\":[],\"questions\":[]}"))
            .isInstanceOfSatisfying(SynthesisValidator.Rejected.class,
                e -> assertThat(e.rule()).isEqualTo(SynthesisValidator.Rule.JSON_OBJECT));
        assertThatThrownBy(() -> validator.validate(input,
            one("오후마다 어깨를 자꾸 만지시는데 어떤 점을 보면 좋을까요?", "[]", "[3]") + " trailing"))
            .isInstanceOfSatisfying(SynthesisValidator.Rejected.class,
                e -> assertThat(e.rule()).isEqualTo(SynthesisValidator.Rule.JSON_OBJECT));
    }

    @Test
    void rejectedCarriesNoSentenceText() {
        assertThatThrownBy(() -> validator.validate(input, one("운동을 더 하면 어깨가 괜찮아질까요?", "[]", "[3]")))
            .hasMessage("FORBIDDEN_WORD");
    }
}
