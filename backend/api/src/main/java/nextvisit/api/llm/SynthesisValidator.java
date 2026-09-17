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
    /**
     * 마크다운 문자, 개행, 유니코드 줄/문단 구분자(U+2028/U+2029), 서식 제어문자({@code \p{Cf}},
     * 예: 제로폭 공백)를 막는다. 서식 제어문자는 "진​단"처럼 금지어 부분 문자열 검사를
     * 피해가는 데 쓰일 수 있어 여기서 통째로 막는다.
     */
    private static final Pattern MARKDOWN = Pattern.compile(
        "[\\r\\n\\u2028\\u2029\\p{Cf}\\\\`*_~#<>\\[\\]|]|^\\s*(?:>|[-+=]|\\d{1,9}[.)](?:\\s|$))");
    private static final Pattern INTERROGATIVE_ENDING = Pattern.compile(
        "(?:나요|까요|가요|습니까|지요|죠)\\?$");
    /** 옛 QuestionOutputGuard의 지시형 패턴에서 '해야'만 뺐다. "어떻게 해야 할까요?"는 보호자의 질문이다. */
    private static final Pattern DIRECTIVE = Pattern.compile(
        "(?:세요|십시오|해\\s*주세요|기\\s*바랍니다|"
            + "해\\s*(?:봐요|볼까요)|면\\s*(?:됩니다|돼요)|[가-힣]+라|(?:어|아)야\\s*(?:합니다|해요|한다))(?=\\s|[,.!?]|$)");
    /** 공백과 유니코드 서식 제어문자({@code \p{Cf}}) 제거 — "치 료" 같은 끼워넣기 회피를 막기 위해서다. */
    private static final Pattern STRIP_FOR_WORD_CHECK = Pattern.compile("[\\s\\p{Cf}]+");
    /**
     * {@code Templates.FORBIDDEN}이 놓치는 ㄹ-불규칙 활용(좋아질/나빠진 등)과, 원인 단정·투약 언급을 막는다.
     * "약"은 독립 명사로 쓰일 때만 막는다 — "예약", "약속", "약간"까지 걸리지 않도록 앞에 한글이 없고
     * (조사나 공백, 문장 끝)이 뒤따를 때만 매치한다.
     */
    private static final Pattern JUDGMENT_OR_CAUSE = Pattern.compile(
        "(?:좋아|나빠|나아)(?:지|진|질|졌|져|짐|질지)"
            + "|때문|진통제|복용|투약"
            + "|(?<![가-힣])약(?:을|은|이|도|\\s|$)");

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
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new Rejected(Rule.QUESTION_FIELDS);
        }
        List<Integer> noteWeeks = new ArrayList<>();
        for (JsonNode week : weeks) {
            if (!week.isIntegralNumber() || !week.canConvertToInt()) {
                throw new Rejected(Rule.QUESTION_FIELDS);
            }
            noteWeeks.add(week.intValue());
        }
        if (new HashSet<>(noteWeeks).size() != noteWeeks.size()) {
            throw new Rejected(Rule.QUESTION_FIELDS);
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
        // 물음표가 정확히 하나, 문장 끝에만 있어야 한다. 지시문이나 다른 문장이 앞에 붙어 있으면 막는다.
        long questionMarks = s.chars().filter(c -> c == '?').count();
        if (questionMarks != 1 || s.indexOf('.') >= 0 || s.indexOf('!') >= 0 || s.indexOf('。') >= 0) {
            throw new Rejected(Rule.QUESTION_MARK);
        }
        if (!INTERROGATIVE_ENDING.matcher(s).find()) {
            throw new Rejected(Rule.QUESTION_MARK);
        }
        // 공백/서식 제어문자를 지운 사본으로만 금지어·판단 표현을 검사한다 — "치 료"처럼 끼워넣어 피해가지 못하게.
        String strippedForWordCheck = STRIP_FOR_WORD_CHECK.matcher(s).replaceAll("");
        if (Templates.containsForbiddenWord(strippedForWordCheck)) {
            throw new Rejected(Rule.FORBIDDEN_WORD);
        }
        if (JUDGMENT_OR_CAUSE.matcher(strippedForWordCheck).find()) {
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
        // \d는 ASCII 숫자만 본다. 전각(３)이나 아랍 숫자(٩) 등 다른 십진 숫자는 \d를 피해 검사를
        // 통과해버리므로, 그런 문자가 하나라도 있으면 그 자체로 거부한다.
        if (containsNonAsciiDigit(q.sentence())) {
            return false;
        }
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
        Set<String> citedWeeks = new HashSet<>();
        q.noteWeeks().forEach(week -> citedWeeks.add(Integer.toString(week)));
        String sentence = q.sentence();
        Matcher m = NUMBER.matcher(sentence);
        while (m.find()) {
            String canon = canonical(m.group());
            if (allowed.contains(canon)) {
                continue;
            }
            // 근거 주차 번호는 "N주"로 쓰일 때만 그 주차를 가리킨다고 인정한다. "3번"처럼 다른 셈에
            // 재사용하면 근거로 대지 않은 숫자와 같다.
            if (citedWeeks.contains(canon) && followedByWeekMarker(sentence, m.end())) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean containsNonAsciiDigit(String s) {
        return s.codePoints().anyMatch(cp -> Character.isDigit(cp) && (cp < '0' || cp > '9'));
    }

    private static boolean followedByWeekMarker(String sentence, int afterIndex) {
        int i = afterIndex;
        while (i < sentence.length() && Character.isWhitespace(sentence.charAt(i))) {
            i++;
        }
        return i < sentence.length() && sentence.charAt(i) == '주';
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
