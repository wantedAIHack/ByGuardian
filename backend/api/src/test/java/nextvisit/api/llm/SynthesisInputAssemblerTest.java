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

    // 전체 재확인 주는 항목마다 메모칸이 있어 보호자가 같은 글을 여러 항목에 남기기 쉽다.
    // 그대로 넘기면 한 문장이 프롬프트에 여섯 번까지 들어가고, 그 반복이 LLM 정리를
    // 세 번 다 실패시켜 보호자가 템플릿 질문만 보게 된다.
    @Test
    void collapsesTheSameNoteRepeatedAcrossItemsIntoOneLine() {
        Map<String, String> sameNoteEverywhere = new LinkedHashMap<>();
        for (String code : List.of("toilet", "dressing", "grooming", "bathing", "feeding")) {
            sameNoteEverywhere.put(code, "합성: 문턱 넘으실 때 오른쪽으로 기우심");
        }
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
        bodies.put(4, body("합성: 문턱 넘으실 때 오른쪽으로 기우심", "EVENING", sameNoteEverywhere));

        SynthesisInput input = new SynthesisInputAssembler(props(4000)).assemble(List.of(), bodies);

        // 시간대는 자유 기록에서, 항목은 첫 항목 메모에서 이어받아 한 줄로 합친다.
        assertThat(input.notes()).containsExactly(new SynthesisInput.NoteLine(
            4, "저녁", ObservationSet.STROKE.item("toilet").label(), "합성: 문턱 넘으실 때 오른쪽으로 기우심"));
    }

    @Test
    void keepsDifferentNotesFromDifferentItems() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("toilet", "합성: 화장실은 혼자 하심");
        notes.put("feeding", "합성: 숟가락을 놓치심");
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
        bodies.put(5, body(null, null, notes));

        SynthesisInput input = new SynthesisInputAssembler(props(4000)).assemble(List.of(), bodies);

        assertThat(input.notes()).containsExactly(
            new SynthesisInput.NoteLine(5, null, ObservationSet.STROKE.item("toilet").label(), "합성: 화장실은 혼자 하심"),
            new SynthesisInput.NoteLine(5, null, ObservationSet.STROKE.item("feeding").label(), "합성: 숟가락을 놓치심"));
    }

    // 실측: 보호자 원문이 총 10자 이하인 주("혼자 하심.", "조금 나음.")에서는 qwen3:4b가
    // 남은 자리를 지어낸 말로 메우고 비문을 낸다("조금 나는 이유가 무엇일까요?"). 26자
    // 이상에서는 6개 표본이 모두 멀쩡했다. 원문이 얇으면 LLM을 부르지 않고 규칙 엔진
    // 템플릿을 그대로 쓴다 — 템플릿은 언제나 근거에 붙어 있다.
    @Test
    void countsNoteCharactersAfterCollapsingDuplicates() {
        Map<String, String> sameNote = new LinkedHashMap<>();
        sameNote.put("toilet", "합성: 혼자 하심");
        sameNote.put("dressing", "합성: 혼자 하심");
        // 같은 글이 세 번 들어와도 한 번만 센다.
        assertThat(SynthesisInputAssembler.noteChars(body("합성: 혼자 하심", "EVENING", sameNote)))
            .isEqualTo("합성: 혼자 하심".length());
    }

    @Test
    void addsUpNoteCharactersAcrossWeeks() {
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
        bodies.put(2, body("합성: 열두 자짜리 메모", null, Map.of()));
        bodies.put(3, body(null, null, Map.of("toilet", "합성: 또 다른 메모")));
        assertThat(SynthesisInputAssembler.noteChars(bodies.values()))
            .isEqualTo("합성: 열두 자짜리 메모".length() + "합성: 또 다른 메모".length());
    }

    @Test
    void thinNotesDoNotEarnAnLlmCall() {
        SortedMap<Integer, SnapshotBody> thin = new TreeMap<>();
        thin.put(2, body("혼자 하심.", "EVENING", Map.of()));
        thin.put(3, body("조금 나음.", "EVENING", Map.of()));
        assertThat(SynthesisInputAssembler.worthSynthesizing(thin.values())).isFalse();

        SortedMap<Integer, SnapshotBody> enough = new TreeMap<>(thin);
        enough.put(4, body("아침에는 혼자 하시는데 저녁에는 도와드렸어요.", "EVENING", Map.of()));
        assertThat(SynthesisInputAssembler.worthSynthesizing(enough.values())).isTrue();
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
