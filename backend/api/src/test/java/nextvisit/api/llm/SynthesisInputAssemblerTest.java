package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.engine.ObservationSet;
import org.junit.jupiter.api.Test;

class SynthesisInputAssemblerTest {

    private static LlmProperties props(int maxChars) {
        return new LlmProperties(true, URI.create("http://localhost:11434/v1"), "qwen3:4b-q4_K_M",
            "ollama", "", "", Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 3000, "none", maxChars);
    }

    private static SnapshotBody body(String free, String tag, Map<String, String> itemNotes) {
        Map<String, SnapshotBody.ItemValues> items = new LinkedHashMap<>();
        itemNotes.forEach((code, note) -> items.put(code, new SnapshotBody.ItemValues(null, null, null, null, note)));
        return new SnapshotBody(items, Map.of(), null, free == null ? null : new SnapshotBody.FreeNote(free, tag));
    }

    private static QuestionCacheBody.Q template(int rank, String sentence) {
        return QuestionCacheBody.Q.template(rank, "STALL", List.of("ambulation"), null, sentence);
    }

    @Test
    void numbersDetectionsByRank() {
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>(Map.of(3, body("합성 메모", "MORNING", Map.of())));
        SynthesisInput input = new SynthesisInputAssembler(props(4000))
            .assemble(List.of(template(1, "가?"), template(2, "나?")), bodies);

        assertThat(input.detections()).extracting(SynthesisInput.Detection::id).containsExactly("D1", "D2");
        assertThat(input.detections().get(1).sentence()).isEqualTo("나?");
        assertThat(input.detectionIds()).containsExactly("D1", "D2");
    }

    @Test
    void includesFreeNoteWithTimeTagAndItemNotesWithLabels() {
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
        bodies.put(3, body("  합성: 오후에 어깨를 자주 만지심  ", "AFTERNOON", Map.of("toilet", "합성: 밤에 한 번 깸")));
        SynthesisInput input = new SynthesisInputAssembler(props(4000)).assemble(List.of(), bodies);

        assertThat(input.notes()).containsExactly(
            new SynthesisInput.NoteLine(3, "오후", null, "합성: 오후에 어깨를 자주 만지심"),
            new SynthesisInput.NoteLine(3, null, ObservationSet.STROKE.item("toilet").label(), "합성: 밤에 한 번 깸"));
        assertThat(input.noteWeeks()).containsExactly(3);
        assertThat(input.hasNotes()).isTrue();
    }

    @Test
    void keepsNewestWeeksWhenNotesExceedTheBudgetAndReturnsThemOldestFirst() {
        String longNote = "가".repeat(300);
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
        bodies.put(1, body(longNote, null, Map.of()));
        bodies.put(2, body(longNote, null, Map.of()));
        bodies.put(3, body(longNote, null, Map.of()));

        SynthesisInput input = new SynthesisInputAssembler(props(700)).assemble(List.of(), bodies);

        assertThat(input.notes()).extracting(SynthesisInput.NoteLine::week).containsExactly(2, 3);
    }

    @Test
    void skipsBlankNotesAndReportsNoNote() {
        SnapshotBody blank = body("   ", "EVENING", Map.of("toilet", ""));
        assertThat(SynthesisInputAssembler.noteLines(4, blank)).isEmpty();
        assertThat(SynthesisInputAssembler.hasNote(blank)).isFalse();
        assertThat(SynthesisInputAssembler.hasNote(null)).isFalse();
    }

    @Test
    void unknownTimeTagBecomesNullLabel() {
        List<SynthesisInput.NoteLine> lines = SynthesisInputAssembler.noteLines(2, body("합성 메모", "NOT_A_TAG", Map.of()));
        assertThat(lines).containsExactly(new SynthesisInput.NoteLine(2, null, null, "합성 메모"));
    }
}
