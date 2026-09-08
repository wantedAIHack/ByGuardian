package nextvisit.api.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.engine.Templates;
import org.springframework.stereotype.Component;

@Component
public class QuestionOutputGuard {

    private static final Set<String> ROOT_FIELDS = Set.of("questions");
    private static final Set<String> QUESTION_FIELDS = Set.of("rank", "sentence");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern MARKDOWN = Pattern.compile(
        "[\\r\\n\\\\`*_~#<>\\[\\]|]|^\\s*(?:>|[-+=]|\\d{1,9}[.)](?:\\s|$))");
    private static final Pattern INTERROGATIVE_ENDING = Pattern.compile(
        "(?:나요|까요|가요|습니까|지요|죠)\\?$");
    private static final Pattern DIRECTIVE = Pattern.compile(
        "(?:세요|십시오|해\\s*주세요|해야|기\\s*바랍니다|"
            + "해\\s*(?:봐요|볼까요)|면\\s*(?:됩니다|돼요)|[가-힣]+라)(?=\\s|[,.!?]|$)");
    private static final List<SurfaceTransformation> SURFACE_TRANSFORMATIONS = List.of(
        new SurfaceTransformation("습니다. ", "는데 "),
        new SurfaceTransformation("입니다. ", "인데 "));

    private final ObjectMapper mapper;

    public QuestionOutputGuard(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Accepted validate(List<QuestionCacheBody.Q> templates, String content) {
        JsonNode root = parseOneObject(content);
        if (!fields(root).equals(ROOT_FIELDS)) {
            throw new Rejected(Rule.ROOT_FIELDS);
        }
        JsonNode questions = root.get("questions");
        if (questions == null || !questions.isArray()) {
            throw new Rejected(Rule.QUESTIONS_ARRAY);
        }
        if (questions.size() != templates.size()) {
            throw new Rejected(Rule.QUESTION_COUNT);
        }

        Map<Integer, QuestionCacheBody.Q> inputs = new HashMap<>();
        for (QuestionCacheBody.Q template : templates) {
            inputs.put(template.rank(), template);
        }
        Map<Integer, String> acceptedByRank = new HashMap<>();
        for (JsonNode candidate : questions) {
            if (!candidate.isObject() || !fields(candidate).equals(QUESTION_FIELDS)
                || !candidate.get("rank").isIntegralNumber()
                || !candidate.get("rank").canConvertToInt()
                || !candidate.get("sentence").isTextual()) {
                throw new Rejected(Rule.QUESTION_FIELDS);
            }
            int rank = candidate.get("rank").intValue();
            QuestionCacheBody.Q template = inputs.get(rank);
            if (template == null || acceptedByRank.containsKey(rank)) {
                throw new Rejected(Rule.RANK_SET);
            }
            String sentence = Normalizer.normalize(
                candidate.get("sentence").textValue(), Normalizer.Form.NFC).strip();
            int length = sentence.codePointCount(0, sentence.length());
            if (length < 1 || length > 200) {
                throw new Rejected(Rule.SENTENCE_LENGTH);
            }
            if (!Templates.isQuestion(sentence)
                || sentence.chars().filter(character -> character == '?').count() != 1) {
                throw new Rejected(Rule.QUESTION_MARK);
            }
            if (Templates.containsForbiddenWord(sentence)) {
                throw new Rejected(Rule.FORBIDDEN_WORD);
            }
            if (MARKDOWN.matcher(sentence).find()) {
                throw new Rejected(Rule.MARKDOWN);
            }
            if (!INTERROGATIVE_ENDING.matcher(sentence).find()
                || DIRECTIVE.matcher(sentence).find()) {
                throw new Rejected(Rule.DIRECTIVE);
            }
            String normalizedTemplate = Normalizer.normalize(
                template.templateSentence(), Normalizer.Form.NFC).strip();
            if (!numberTokens(normalizedTemplate).equals(numberTokens(sentence))) {
                throw new Rejected(Rule.NUMBER_TOKENS);
            }
            if (!isPermittedSurfaceRewrite(normalizedTemplate, sentence)) {
                throw new Rejected(Rule.SURFACE_REWRITE);
            }
            acceptedByRank.put(rank, sentence);
        }
        if (!acceptedByRank.keySet().equals(inputs.keySet())) {
            throw new Rejected(Rule.RANK_SET);
        }

        List<Rewrite> ordered = new ArrayList<>();
        for (QuestionCacheBody.Q template : templates) {
            ordered.add(new Rewrite(template.rank(), acceptedByRank.get(template.rank())));
        }
        return new Accepted(List.copyOf(ordered));
    }

    private JsonNode parseOneObject(String content) {
        if (content == null || content.isBlank()) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
        try (JsonParser parser = mapper.createParser(content)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new Rejected(Rule.JSON_OBJECT);
            }
            return root;
        } catch (IOException e) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static Map<String, Integer> numberTokens(String sentence) {
        Map<String, Integer> tokens = new HashMap<>();
        Matcher matcher = NUMBER.matcher(sentence);
        while (matcher.find()) {
            tokens.merge(matcher.group(), 1, Integer::sum);
        }
        return tokens;
    }

    /**
     * The LLM may keep the normalized template unchanged or use one of the two
     * explicitly enumerated final-clause joins. Everything outside that join is
     * compared byte-for-byte after NFC normalization, so novel facts and advice
     * fail closed instead of relying on an open-ended Korean semantic analysis.
     */
    private static boolean isPermittedSurfaceRewrite(String template, String candidate) {
        if (candidate.equals(template)) {
            return true;
        }
        for (SurfaceTransformation transformation : SURFACE_TRANSFORMATIONS) {
            int split = template.lastIndexOf(transformation.from());
            if (split < 1) {
                continue;
            }
            String questionTail = template.substring(split + transformation.from().length());
            if (questionTail.isBlank() || !questionTail.endsWith("?")
                || questionTail.chars().filter(character -> character == '?').count() != 1) {
                continue;
            }
            String rewritten = template.substring(0, split) + transformation.to() + questionTail;
            if (candidate.equals(rewritten)) {
                return true;
            }
        }
        return false;
    }

    public enum Rule {
        JSON_OBJECT,
        ROOT_FIELDS,
        QUESTIONS_ARRAY,
        QUESTION_COUNT,
        QUESTION_FIELDS,
        RANK_SET,
        SENTENCE_LENGTH,
        QUESTION_MARK,
        MARKDOWN,
        FORBIDDEN_WORD,
        DIRECTIVE,
        NUMBER_TOKENS,
        SURFACE_REWRITE
    }

    private record SurfaceTransformation(String from, String to) {}

    public record Rewrite(int rank, String sentence) {}

    public record Accepted(List<Rewrite> rewrites) {}

    public static final class Rejected extends RuntimeException {
        private final Rule rule;

        public Rejected(Rule rule) {
            super(rule.name());
            this.rule = rule;
        }

        public Rule rule() {
            return rule;
        }
    }
}
