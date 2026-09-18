package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SynthesisFixtureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixturesMatchTheThreeSyntheticEvaluationShapes() throws Exception {
        LiveSynthesisEvaluation.Fixture caseA = fixture("case-a");
        LiveSynthesisEvaluation.Fixture caseB = fixture("case-b");
        LiveSynthesisEvaluation.Fixture caseC = fixture("case-c");

        assertThat(caseA.templates()).hasSize(1);
        assertThat(caseA.weeks()).containsOnlyKeys(1, 2, 3, 4, 5, 6);
        assertThat(noteWeeks(caseA)).containsExactly(1, 3, 4, 6);

        assertThat(caseB.templates()).hasSize(2);
        assertThat(noteWeeks(caseB)).containsExactly(1, 2, 3, 4);
        assertThat(caseB.weeks().values().stream()
            .filter(week -> week.freeNote() != null)
            .filter(week -> "EVENING".equals(week.freeNote().timeTag())))
            .hasSize(3);
        assertThat(allNoteTexts(caseB)).anyMatch(text -> text.contains("2번"));

        assertThat(caseC.templates()).isEmpty();
        assertThat(noteWeeks(caseC)).containsExactly(1, 2, 3, 4, 5);
        assertThat(allNoteTexts(caseC)).anyMatch(text -> text.contains("식사 속도"));
        assertThat(allNoteTexts(caseC)).anyMatch(text -> text.contains("숟가락"));
        assertThat(allNoteTexts(caseC)).anyMatch(text -> text.contains("말수"));

        for (String caseName : LiveSynthesisEvaluation.CASES) {
            assertThat(allNoteTexts(fixture(caseName)))
                .allSatisfy(text -> assertThat(text.length()).isBetween(20, 120));
        }
    }

    @Test
    void fixturesFlowThroughTheProductionInputAssembler() throws Exception {
        LlmProperties properties = new LlmProperties(false,
            java.net.URI.create("http://localhost:11434/v1"), "qwen3:4b-q4_K_M", "", "", "",
            java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(45), 3, 3000, "none", 4000);
        SynthesisInputAssembler assembler = new SynthesisInputAssembler(properties);

        assertThat(assembler.assemble(fixture("case-a").toTemplates(), fixture("case-a").toSnapshots()).noteWeeks())
            .containsExactly(1, 3, 4, 6);
        assertThat(assembler.assemble(fixture("case-b").toTemplates(), fixture("case-b").toSnapshots()).detections())
            .hasSize(2);
        assertThat(assembler.assemble(fixture("case-c").toTemplates(), fixture("case-c").toSnapshots()).detections())
            .isEmpty();
    }

    @Test
    void trackedLiveArtifactsHaveEveryCaseAndRunWithConsistentResults() throws Exception {
        Path artifactDir = Path.of("..", "..", "docs", "qa", "artifacts", "2026-09-18-synthesis");
        for (String fileName : List.of("results-none.json", "results-default.json")) {
            Path artifact = artifactDir.resolve(fileName);
            assertThat(Files.isRegularFile(artifact)).as(fileName).isTrue();
            JsonNode runs = mapper.readTree(artifact.toFile());
            assertThat(runs.isArray()).as(fileName).isTrue();
            assertThat(runs).hasSize(9);

            Set<String> caseRuns = new LinkedHashSet<>();
            for (JsonNode run : runs) {
                caseRuns.add(run.path("case").asText() + "#" + run.path("run").asInt());
                assertThat(run.path("elapsedMs").asLong()).isPositive();
                int promptTokens = run.path("promptTokens").asInt(-1);
                int completionTokens = run.path("completionTokens").asInt(-1);
                int totalTokens = run.path("totalTokens").asInt(-1);
                assertThat(promptTokens).isPositive();
                assertThat(completionTokens).isPositive();
                assertThat(totalTokens).isEqualTo(promptTokens + completionTokens);

                if (run.path("accepted").asBoolean()) {
                    assertThat(run.get("rule").isNull()).isTrue();
                    assertThat(run.path("questions").isArray()).isTrue();
                    assertThat(run.path("questions")).isNotEmpty();
                } else {
                    assertThat(run.path("rule").asText()).isNotBlank();
                    assertThat(run.path("questions")).isEmpty();
                }
            }
            assertThat(caseRuns).containsExactlyInAnyOrder(
                "case-a#1", "case-a#2", "case-a#3",
                "case-b#1", "case-b#2", "case-b#3",
                "case-c#1", "case-c#2", "case-c#3");
        }
    }

    private LiveSynthesisEvaluation.Fixture fixture(String name) throws Exception {
        return LiveSynthesisEvaluation.loadFixture(mapper, name);
    }

    private static List<Integer> noteWeeks(LiveSynthesisEvaluation.Fixture fixture) {
        return fixture.weeks().entrySet().stream()
            .filter(entry -> !texts(entry.getValue()).isEmpty())
            .map(Map.Entry::getKey)
            .toList();
    }

    private static List<String> allNoteTexts(LiveSynthesisEvaluation.Fixture fixture) {
        return fixture.weeks().values().stream().flatMap(week -> texts(week).stream()).toList();
    }

    private static List<String> texts(LiveSynthesisEvaluation.Week week) {
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        if (week.freeNote() != null) {
            texts.add(week.freeNote().text());
        }
        texts.addAll(week.itemNotes().values());
        return texts;
    }
}
