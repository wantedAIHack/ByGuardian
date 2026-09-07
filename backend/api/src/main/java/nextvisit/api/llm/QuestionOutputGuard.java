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
        "(?m)[`*_~\\[\\]]|^\\s*(?:#{1,6}\\s|[-+]\\s|>\\s|\\d+\\.\\s)");
    private static final Pattern DIRECTIVE = Pattern.compile(
        "(?:하세요|하십시오|해 ?주세요|가세요|받으세요|드세요|복용하세요|해야 합니다|해야 해요)");

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
                candidate.get("sentence").textValue().trim(), Normalizer.Form.NFC);
            int length = sentence.codePointCount(0, sentence.length());
            if (length < 1 || length > 200) {
                throw new Rejected(Rule.SENTENCE_LENGTH);
            }
            if (!Templates.isQuestion(sentence)
                || sentence.chars().filter(character -> character == '?').count() != 1) {
                throw new Rejected(Rule.QUESTION_MARK);
            }
            if (MARKDOWN.matcher(sentence).find()) {
                throw new Rejected(Rule.MARKDOWN);
            }
            if (Templates.containsForbiddenWord(sentence)) {
                throw new Rejected(Rule.FORBIDDEN_WORD);
            }
            if (DIRECTIVE.matcher(sentence).find()) {
                throw new Rejected(Rule.DIRECTIVE);
            }
            if (!numberTokens(template.templateSentence()).equals(numberTokens(sentence))) {
                throw new Rejected(Rule.NUMBER_TOKENS);
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
        NUMBER_TOKENS
    }

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
