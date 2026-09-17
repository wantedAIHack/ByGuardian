package nextvisit.api.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nextvisit.engine.Templates;
import org.springframework.stereotype.Component;

/**
 * 설계 3.3: LLM 정리 결과를 받아들일지 정한다. 하나라도 어기면 그 시도는 실패다.
 * 예외 메시지에는 규칙 이름만 담는다 — 문장과 원문을 로그로 흘리지 않기 위해서다.
 */
@Component
public class SynthesisValidator {

    static final int MAX_QUESTIONS = 3;
    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 160;

    private static final Set<String> ROOT_FIELDS = Set.of("questions");
    private static final Set<String> QUESTION_FIELDS = Set.of("sentence", "detections", "noteWeeks");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern MARKDOWN = Pattern.compile(
        "[\\r\\n\\\\`*_~#<>\\[\\]|]|^\\s*(?:>|[-+=]|\\d{1,9}[.)](?:\\s|$))");
    private static final Pattern INTERROGATIVE_ENDING = Pattern.compile(
        "(?:나요|까요|가요|습니까|지요|죠)\\?$");
    /** 옛 QuestionOutputGuard의 지시형 패턴에서 '해야'만 뺐다. "어떻게 해야 할까요?"는 보호자의 질문이다. */
    private static final Pattern DIRECTIVE = Pattern.compile(
        "(?:세요|십시오|해\\s*주세요|기\\s*바랍니다|"
            + "해\\s*(?:봐요|볼까요)|면\\s*(?:됩니다|돼요)|[가-힣]+라)(?=\\s|[,.!?]|$)");

    private final ObjectMapper mapper;

    public SynthesisValidator(ObjectMapper mapper) {
        this.mapper = mapper.copy()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public Accepted validate(SynthesisInput input, String content) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
        if (root == null || !root.isObject()) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
        if (!fieldNames(root).equals(ROOT_FIELDS)) {
            throw new Rejected(Rule.ROOT_FIELDS);
        }
        JsonNode questions = root.get("questions");
        if (!questions.isArray()) {
            throw new Rejected(Rule.QUESTIONS_ARRAY);
        }
        if (questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            throw new Rejected(Rule.QUESTION_COUNT);
        }
        List<Question> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : questions) {
            Question question = parse(node);
            check(input, question);
            if (!seen.add(question.sentence())) {
                throw new Rejected(Rule.DUPLICATE);
            }
            accepted.add(question);
        }
        return new Accepted(List.copyOf(accepted));
    }

    private static Question parse(JsonNode node) {
        if (!node.isObject() || !fieldNames(node).equals(QUESTION_FIELDS)) {
            throw new Rejected(Rule.QUESTION_FIELDS);
        }
        JsonNode sentence = node.get("sentence");
        JsonNode detections = node.get("detections");
        JsonNode weeks = node.get("noteWeeks");
        if (!sentence.isTextual() || !detections.isArray() || !weeks.isArray()) {
            throw new Rejected(Rule.QUESTION_FIELDS);
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : detections) {
            if (!id.isTextual()) {
                throw new Rejected(Rule.QUESTION_FIELDS);
            }
            ids.add(id.textValue());
        }
        List<Integer> noteWeeks = new ArrayList<>();
        for (JsonNode week : weeks) {
            if (!week.isIntegralNumber() || !week.canConvertToInt()) {
                throw new Rejected(Rule.QUESTION_FIELDS);
            }
            noteWeeks.add(week.intValue());
        }
        String normalized = Normalizer.normalize(sentence.textValue(), Normalizer.Form.NFC).strip();
        return new Question(normalized, List.copyOf(ids), List.copyOf(noteWeeks));
    }

    private static void check(SynthesisInput input, Question q) {
        String s = q.sentence();
        if (s.length() < MIN_LENGTH || s.length() > MAX_LENGTH) {
            throw new Rejected(Rule.SENTENCE_LENGTH);
        }
        if (MARKDOWN.matcher(s).find()) {
            throw new Rejected(Rule.MARKDOWN);
        }
        if (!INTERROGATIVE_ENDING.matcher(s).find()) {
            throw new Rejected(Rule.QUESTION_MARK);
        }
        if (Templates.containsForbiddenWord(s)) {
            throw new Rejected(Rule.FORBIDDEN_WORD);
        }
        if (DIRECTIVE.matcher(s).find()) {
            throw new Rejected(Rule.DIRECTIVE);
        }
        if (q.detections().isEmpty() && q.noteWeeks().isEmpty()) {
            throw new Rejected(Rule.BASIS_EMPTY);
        }
        if (!input.detectionIds().containsAll(q.detections())) {
            throw new Rejected(Rule.UNKNOWN_DETECTION);
        }
        if (!input.noteWeeks().containsAll(q.noteWeeks())) {
            throw new Rejected(Rule.UNKNOWN_NOTE_WEEK);
        }
        if (!numbersSupported(input, q)) {
            throw new Rejected(Rule.UNSUPPORTED_NUMBER);
        }
    }

    private static boolean numbersSupported(SynthesisInput input, Question q) {
        Set<String> allowed = new HashSet<>();
        for (SynthesisInput.Detection d : input.detections()) {
            if (q.detections().contains(d.id())) {
                collectNumbers(d.sentence(), allowed);
            }
        }
        for (SynthesisInput.NoteLine n : input.notes()) {
            if (q.noteWeeks().contains(n.week())) {
                collectNumbers(n.text(), allowed);
            }
        }
        q.noteWeeks().forEach(week -> allowed.add(Integer.toString(week)));
        Matcher m = NUMBER.matcher(q.sentence());
        while (m.find()) {
            if (!allowed.contains(canonical(m.group()))) {
                return false;
            }
        }
        return true;
    }

    private static void collectNumbers(String text, Set<String> into) {
        Matcher m = NUMBER.matcher(Normalizer.normalize(text, Normalizer.Form.NFC));
        while (m.find()) {
            into.add(canonical(m.group()));
        }
    }

    private static String canonical(String digits) {
        return new BigInteger(digits).toString();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    public record Question(String sentence, List<String> detections, List<Integer> noteWeeks) {}

    public record Accepted(List<Question> questions) {}

    public enum Rule {
        JSON_OBJECT,
        ROOT_FIELDS,
        QUESTIONS_ARRAY,
        QUESTION_COUNT,
        QUESTION_FIELDS,
        SENTENCE_LENGTH,
        MARKDOWN,
        QUESTION_MARK,
        FORBIDDEN_WORD,
        DIRECTIVE,
        BASIS_EMPTY,
        UNKNOWN_DETECTION,
        UNKNOWN_NOTE_WEEK,
        UNSUPPORTED_NUMBER,
        DUPLICATE
    }

    public static final class Rejected extends RuntimeException {
        private final Rule rule;

        public Rejected(Rule rule) {
            super(rule.name(), null, false, false);
            this.rule = rule;
        }

        public Rule rule() {
            return rule;
        }
    }
}
