package nextvisit.api.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.snapshots.SnapshotBody;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Tag("live-llm")
class LiveSynthesisEvaluation {

    static final List<String> CASES = List.of("case-a", "case-b", "case-c");
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(300);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void measuresEachSyntheticCaseThreeTimes() throws Exception {
        String baseUrl = System.getProperty("nextvisit.live.baseUrl", "").strip();
        Assumptions.assumeTrue(!baseUrl.isEmpty(), "nextvisit.live.baseUrl is required");
        String effort = System.getProperty("nextvisit.live.effort", "none");
        int maxTokens = Integer.parseInt(System.getProperty("nextvisit.live.maxTokens", "3000"));
        Path outputDir = Path.of(System.getProperty("nextvisit.live.outputDir", "build/live-llm"));

        LlmProperties properties = new LlmProperties(true, URI.create(baseUrl),
            "qwen3:4b-q4_K_M", "ollama", "", "", Duration.ofSeconds(3), READ_TIMEOUT,
            1, maxTokens, effort, 4000);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        requestFactory.setReadTimeout(properties.readTimeout());
        UsageCapture usageCapture = new UsageCapture(mapper);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, mapper,
            RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
                .requestInterceptor((request, body, execution) -> {
                    ClientHttpResponse response = execution.execute(request, body);
                    usageCapture.capture(response);
                    return response;
                })
                .build());
        SynthesisInputAssembler assembler = new SynthesisInputAssembler(properties);
        QuestionSynthesisPrompt prompts = new QuestionSynthesisPrompt(mapper);
        SynthesisValidator validator = new SynthesisValidator(mapper);
        List<Result> results = new ArrayList<>();

        for (String caseName : CASES) {
            Fixture fixture = loadFixture(mapper, caseName);
            SynthesisInput input = assembler.assemble(fixture.toTemplates(), fixture.toSnapshots());
            LlmPrompt prompt = prompts.build(input, Optional.empty());
            for (int run = 1; run <= 3; run++) {
                results.add(measure(caseName, run, input, prompt, client, validator, usageCapture));
            }
        }

        Path output = outputDir.resolve("results-" + effortLabel(effort) + ".json");
        Files.createDirectories(outputDir);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
    }

    private Result measure(String caseName, int run, SynthesisInput input, LlmPrompt prompt,
                           OpenAiCompatibleLlmClient client, SynthesisValidator validator,
                           UsageCapture usageCapture) {
        long started = System.nanoTime();
        usageCapture.clear();
        try {
            String content = client.complete(prompt);
            Usage usage = usageCapture.current();
            try {
                SynthesisValidator.Accepted accepted = validator.validate(input, content);
                return new Result(caseName, run, elapsedMillis(started), true, null,
                    accepted.questions(), usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
            } catch (SynthesisValidator.Rejected rejected) {
                return new Result(caseName, run, elapsedMillis(started), false,
                    rejected.rule().name(), List.of(), usage.promptTokens(),
                    usage.completionTokens(), usage.totalTokens());
            }
        } catch (LlmClientException failure) {
            return new Result(caseName, run, elapsedMillis(started), false,
                "CLIENT_" + failure.code().name(), List.of(), null, null, null);
        }
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String effortLabel(String effort) {
        return effort.isBlank() ? "default" : effort.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    static Fixture loadFixture(ObjectMapper mapper, String caseName) throws IOException {
        String resource = "/synthesis-fixtures/" + caseName + ".json";
        try (InputStream input = LiveSynthesisEvaluation.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing synthesis fixture " + caseName);
            }
            return mapper.readValue(input, Fixture.class);
        }
    }

    record Fixture(List<Template> templates, Map<Integer, Week> weeks) {
        List<QuestionCacheBody.Q> toTemplates() {
            return templates.stream()
                .map(template -> QuestionCacheBody.Q.template(template.rank(), template.type(),
                    template.items(), null, template.sentence()))
                .toList();
        }

        SortedMap<Integer, SnapshotBody> toSnapshots() {
            SortedMap<Integer, SnapshotBody> snapshots = new TreeMap<>();
            weeks.forEach((number, week) -> snapshots.put(number, week.toSnapshot()));
            return snapshots;
        }
    }

    record Template(int rank, String type, List<String> items, String sentence) {}

    record Week(FreeNote freeNote, Map<String, String> itemNotes) {
        SnapshotBody toSnapshot() {
            Map<String, SnapshotBody.ItemValues> items = new LinkedHashMap<>();
            itemNotes.forEach((code, note) -> items.put(code,
                new SnapshotBody.ItemValues(null, null, null, null, note)));
            SnapshotBody.FreeNote note = freeNote == null
                ? null : new SnapshotBody.FreeNote(freeNote.text(), freeNote.timeTag());
            return new SnapshotBody(items, Map.of(), null, note);
        }
    }

    record FreeNote(String text, String timeTag) {}

    record Result(@JsonProperty("case") String caseName, int run, long elapsedMs, boolean accepted, String rule,
                  List<SynthesisValidator.Question> questions, Integer promptTokens,
                  Integer completionTokens, Integer totalTokens) {}

    private record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        private static final Usage EMPTY = new Usage(null, null, null);
    }

    private static final class UsageCapture {
        private final ObjectMapper mapper;
        private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.EMPTY);

        private UsageCapture(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        private void capture(ClientHttpResponse response) throws IOException {
            try {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(response.getBody()).path("usage");
                usage.set(new Usage(nullableInt(node.get("prompt_tokens")),
                    nullableInt(node.get("completion_tokens")), nullableInt(node.get("total_tokens"))));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                usage.set(Usage.EMPTY);
            }
        }

        private void clear() {
            usage.set(Usage.EMPTY);
        }

        private Usage current() {
            return usage.get();
        }

        private static Integer nullableInt(com.fasterxml.jackson.databind.JsonNode value) {
            return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue() : null;
        }
    }
}
