package nextvisit.api.snapshots;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import nextvisit.api.catalog.CatalogController;
import nextvisit.api.cases.*;
import nextvisit.api.common.Json;
import nextvisit.api.common.ValidationException;
import nextvisit.api.engine.EngineBridge;
import nextvisit.api.llm.SynthesisInputAssembler;
import nextvisit.api.progress.TrajectoryMapper;
import nextvisit.engine.Axis;
import nextvisit.engine.Labels;
import org.junit.jupiter.api.Test;

class QuestionnaireV2Test {
    final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final Json json = new Json(mapper);
    final SnapshotAssembler assembler = new SnapshotAssembler();
    final CaseEntity kase = new CaseEntity("stroke", LocalDate.of(2026, 9, 5), Diagnosis.STROKE,
        PareticSide.UNKNOWN, VerbalDifficulty.NONE, null, "hash", Instant.EPOCH);
    SnapshotBody legacy() {
        Map<String, ItemInput> inputs = new LinkedHashMap<>();
        for (String code : List.of("transfer", "ambulation", "stairs", "toilet", "dressing", "grooming", "bathing", "feeding"))
            inputs.put(code, new ItemInput(2, List.of("transfer", "ambulation", "stairs").contains(code) ? 1 : null, 2, null, null));
        return assembler.build(kase, inputs, null, null, null, null);
    }
    ItemInput input(Map<String, List<String>> answers, String note) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("questionnaireVersion", 2); in.put("answers", answers); in.put("note", note);
        return mapper.convertValue(in, ItemInput.class);
    }
    SnapshotBody apply(String code, Map<String, List<String>> answers) {
        return assembler.build(kase, Map.of(code, input(answers, null)), legacy(), null, null, null);
    }
    Snapshot snap(int week, SnapshotBody body) {
        return new Snapshot(UUID.randomUUID(), week, SnapshotKind.WEEKLY, false, UUID.randomUUID(), json.toJson(body), Instant.EPOCH);
    }
    @Test void catalogHasBathingQuestionnaireAndEightCategories() {
        var catalog = mapper.valueToTree(new CatalogController().catalog());
        assertEquals(8, catalog.path("items").size());
        int count = 0;
        for (var item : catalog.path("items")) if (item.path("questionnaire").path("version").asInt() == 2) count++;
        assertEquals(5, count);

        var bathing = catalog.path("items").get(6);
        assertEquals("bathing", bathing.path("code").asText());
        var question = bathing.path("questionnaire").path("questions").get(0);
        assertEquals("assistance", question.path("code").asText());
        assertEquals("목욕할 때 어느 정도 참여하셨나요?", question.path("label").asText());
        List<String> labels = new ArrayList<>();
        question.path("options").forEach(option -> labels.add(option.path("label").asText()));
        assertEquals(List.of(
            "머리 감기·헹구기·닦기 등을 스스로 수행",
            "준비 과정이나 안전을 위한 감시하에 스스로 수행",
            "각 활동에서 신체적인 도움이 필요함 (예: 머리를 감겨주거나 손이 닿지 않는 등 부위를 도와줌)",
            "가슴이나 팔처럼 손이 닿는 부위만 혼자 가능",
            "모든 과정에 참여하지 않음",
            "직접 보지 못함",
            "이번 주 하지 않음"), labels);
    }
    @Test void unknownAndNotPerformedRoundTripWithoutLegacyScores() {
        var body = apply("grooming", Map.of("washing", List.of("unknown"), "brushing", List.of("not_performed")));
        var stored = mapper.valueToTree(body.items().get("grooming"));
        assertEquals(2, stored.path("questionnaireVersion").asInt());
        assertEquals("unknown", stored.path("answers").path("washing").get(0).asText());
        assertEquals("CONFIRMED", stored.path("answerSource").asText());
        assertNull(body.items().get("grooming").level());
        assertEquals(body, json.fromJson(json.toJson(body), SnapshotBody.class));
    }
    @Test void bathingAnswerRoundTripsToReportAndEndsLegacyAnalysis() {
        var updated = apply("bathing", Map.of("assistance", List.of("2")));
        var stored = mapper.valueToTree(updated.items().get("bathing"));
        assertEquals(2, stored.path("questionnaireVersion").asInt());
        assertEquals("2", stored.path("answers").path("assistance").get(0).asText());
        assertTrue(stored.path("level").isNull());

        var snapshots = List.of(snap(1, legacy()), snap(2, updated));
        var report = new TrajectoryMapper(json).items(snapshots).stream()
            .filter(item -> item.code().equals("bathing:v2")).findFirst().orElseThrow();
        assertEquals(List.of("각 활동에서 신체적인 도움이 필요함 (예: 머리를 감겨주거나 손이 닿지 않는 등 부위를 도와줌)"),
            report.observations().get(0).answers());
        assertFalse(new EngineBridge(json).toCaseInput(kase, snapshots).series().containsKey("bathing"));
    }
    @Test void tubeOnlySkipsOralAssistanceAndRejectsInactiveAnswers() {
        assertDoesNotThrow(() -> apply("feeding", Map.of("route", List.of("tube"))));
        assertThrows(ValidationException.class, () -> apply("feeding", Map.of("route", List.of("tube"), "assistance", List.of("4"))));
        assertThrows(ValidationException.class, () -> apply("feeding", Map.of("route", List.of("oral"))));
    }
    @Test void variationRequiresObservedAssistance() {
        for (String unavailable : List.of("unknown", "not_performed")) {
            assertThrows(ValidationException.class, () -> apply("dressing", Map.of(
                "assistance", List.of(unavailable), "variation", List.of("stable"))));
            assertThrows(ValidationException.class, () -> apply("feeding", Map.of(
                "route", List.of("oral"), "assistance", List.of(unavailable), "variation", List.of("stable"))));
        }
        for (String route : List.of("tube", "unknown", "not_performed")) {
            assertThrows(ValidationException.class, () -> apply("feeding", Map.of(
                "route", List.of(route), "variation", List.of("stable"))));
        }
        assertDoesNotThrow(() -> apply("dressing", Map.of("assistance", List.of("4"), "variation", List.of("stable"))));
        assertDoesNotThrow(() -> apply("feeding", Map.of("route", List.of("both"),
            "assistance", List.of("2"), "variation", List.of("more"))));
    }
    @Test void rejectsUnknownDuplicateAndAmbiguousAnswers() {
        assertThrows(ValidationException.class, () -> apply("dressing", Map.of("assistance", List.of("unknown", "4"))));
        assertThrows(ValidationException.class, () -> apply("dressing", Map.of("assistance", List.of("5"))));
        assertThrows(ValidationException.class, () -> apply("dressing", Map.of("assistance", List.of("4"), "invented", List.of("x"))));
        assertThrows(ValidationException.class, () -> apply("dressing", Map.of("assistance", List.of("2"), "parts", List.of("unknown", "upper"))));
        assertThrows(ValidationException.class, () -> apply("dressing", Map.of("assistance", List.of("2"), "parts", List.of("upper", "upper"))));
        assertDoesNotThrow(() -> apply("dressing", Map.of("assistance", List.of("2"), "parts", List.of("upper", "lower"))));
    }
    @Test void carriesAnswersDropsNotesAndRejectsDowngrade() {
        var first = assembler.build(kase, Map.of("dressing", input(Map.of("assistance", List.of("3")), "준비를 도와드림")), legacy(), null, null, null);
        var carried = assembler.build(kase, Map.of(), first, null, null, null);
        var stored = mapper.valueToTree(carried.items().get("dressing"));
        assertEquals(2, stored.path("questionnaireVersion").asInt());
        assertEquals("CARRIED", stored.path("answerSource").asText());
        assertEquals("3", stored.path("answers").path("assistance").get(0).asText());
        assertNull(carried.items().get("dressing").note());
        assertEquals(1, SynthesisInputAssembler.noteLines(2, first).size());
        assertEquals(0, SynthesisInputAssembler.noteLines(3, carried).size());
        assertThrows(ValidationException.class, () -> assembler.build(kase,
            Map.of("dressing", new ItemInput(2, null, 2, null, null)), first, null, null, null));
    }
    @Test void noteLimitAppliesToV1AndV2() {
        assertThrows(ValidationException.class, () -> assembler.build(kase,
            Map.of("dressing", input(Map.of("assistance", List.of("4")), "x".repeat(501))), legacy(), null, null, null));
        assertDoesNotThrow(() -> assembler.build(kase,
            Map.of("dressing", input(Map.of("assistance", List.of("4")), "x".repeat(500))), legacy(), null, null, null));
        assertThrows(ValidationException.class, () -> assembler.build(kase,
            Map.of("bathing", new ItemInput(2, null, 2, null, "x".repeat(501))), legacy(), null, null, null));
    }
    @Test void versionBoundaryKeepsHistoricalLabelsAndStopsStaleAnalysis() {
        var snapshots = List.of(snap(1, legacy()), snap(2, apply("dressing", Map.of("assistance", List.of("4")))));
        var result = mapper.valueToTree(new TrajectoryMapper(json).items(snapshots));
        var old = java.util.stream.StreamSupport.stream(result.spliterator(), false)
            .filter(t -> t.path("code").asText().equals("dressing")).findFirst().orElseThrow();
        var v2 = java.util.stream.StreamSupport.stream(result.spliterator(), false)
            .filter(t -> t.path("code").asText().equals("dressing:v2")).findFirst().orElseThrow();
        assertEquals(Labels.of(Axis.LEVEL, 2), old.path("axes").get(0).path("values").get(0).path("label").asText());
        assertEquals(1, old.path("axes").get(0).path("values").size());
        assertEquals(2, v2.path("versionStartWeek").asInt());
        assertFalse(v2.path("changed").asBoolean());
        var engine = new EngineBridge(json).toCaseInput(kase, snapshots);
        assertFalse(engine.series().containsKey("dressing"));
        assertTrue(engine.series().containsKey("bathing"));
        assertTrue(engine.series().containsKey("transfer"));
        assertDoesNotThrow(() -> new EngineBridge(json).run(kase, snapshots));
    }
    @Test void reportIncludesOriginalNoteOnlyOnItsConfirmedWeek() {
        var first = assembler.build(kase, Map.of("dressing", input(Map.of("assistance", List.of("3")), "  단추를 도와드림  ")), legacy(), null, null, null);
        var carried = assembler.build(kase, Map.of(), first, null, null, null);
        var reports = new TrajectoryMapper(json).items(List.of(snap(2, first), snap(3, carried)));
        var observation = reports.stream().filter(t -> t.code().equals("dressing:v2")).findFirst().orElseThrow();
        var notes = mapper.valueToTree(observation).path("observations");
        var matches = java.util.stream.StreamSupport.stream(notes.spliterator(), false)
            .filter(t -> t.path("question").asText().equals("note")).toList();
        assertEquals(1, matches.size());
        assertEquals(2, matches.get(0).path("week").asInt());
        assertEquals("단추를 도와드림", matches.get(0).path("answers").get(0).asText());
        assertEquals("CONFIRMED", matches.get(0).path("source").asText());
    }

    @Test void rejectsUnsupportedVersionsLegacyAxesAndMalformedSelections() {
        var v3 = mapper.convertValue(Map.of("questionnaireVersion", 3, "answers", Map.of("assistance", List.of("4"))), ItemInput.class);
        assertThrows(ValidationException.class, () -> assembler.build(kase, Map.of("dressing", v3), legacy(), null, null, null));
        var v2WithAxes = mapper.convertValue(Map.of("questionnaireVersion", 2, "level", 3,
            "answers", Map.of("assistance", List.of("4"))), ItemInput.class);
        assertThrows(ValidationException.class, () -> assembler.build(kase, Map.of("dressing", v2WithAxes), legacy(), null, null, null));
        assertDoesNotThrow(() -> apply("bathing", Map.of("assistance", List.of("4"))));
        assertThrows(ValidationException.class, () -> apply("dressing", Map.of("assistance", Arrays.asList((String) null))));
        assertThrows(ValidationException.class, () -> apply("feeding", Map.of("route", List.of("tube"), "parts", List.of("cup"))));
        assertThrows(ValidationException.class, () -> apply("grooming", Map.of("washing", List.of("4"), "brushing", List.of("4"), "risks", List.of("none", "fall"))));
        Map<String, ItemInput> nullItem = new LinkedHashMap<>();
        nullItem.put("dressing", null);
        assertThrows(ValidationException.class, () -> assembler.build(kase, nullItem, legacy(), null, null, null));
    }

}
