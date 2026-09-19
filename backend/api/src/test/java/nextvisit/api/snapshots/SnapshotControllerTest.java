package nextvisit.api.snapshots;

import static nextvisit.api.ApiTestSupport.baselineItems;
import static nextvisit.api.ApiTestSupport.getMe;
import static nextvisit.api.ApiTestSupport.json;
import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.putJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import nextvisit.api.ApiTestSupport.Onboarded;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import nextvisit.api.questions.QuestionCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SnapshotControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @Autowired SnapshotRepository snapshots;
    @Autowired QuestionCacheRepository caches;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfig.DEFAULT_TODAY);
    }

    private static Map<String, Object> weekly(boolean noChange, Map<String, Object> changed, Object painSignal) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("noChange", noChange);
        b.put("changedItems", changed);
        b.put("painSignal", painSignal);
        b.put("sleep", 1);
        b.put("freeNote", null);
        return b;
    }

    private static Map<String, Object> item(Integer level, Integer aid, Integer consistency, Integer hand, String note) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("level", level);
        v.put("aid", aid);
        v.put("consistency", consistency);
        v.put("hand", hand);
        v.put("note", note);
        return v;
    }

    private SnapshotBody body(String caseId, int week) throws Exception {
        return mapper.readValue(snapshots.findByCaseIdAndWeek(UUID.fromString(caseId), week).orElseThrow().getBody(), SnapshotBody.class);
    }

    @Test
    void weekOneIsBaselineOnly() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        mvc.perform(putJson(o.token(), "/me/weeks/1", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASELINE_ONLY"));
    }

    @Test
    void weekMustMatchToday() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEEK_MISMATCH"));
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/3", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEEK_MISMATCH"));
    }

    @Test
    void noChangeCopiesEverythingAsCarriedAndRefreshesQuestions() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.week").value(2))
            .andExpect(jsonPath("$.kind").value("WEEKLY"))
            .andExpect(jsonPath("$.questionsRefreshed").value(true));
        SnapshotBody b = body(o.caseId(), 2);
        for (var e : b.items().entrySet()) {
            assertEquals("CARRIED", e.getValue().level().source(), e.getKey());
            assertNull(e.getValue().note());
        }
        assertEquals(2, b.items().get("toilet").level().value());
        assertTrue(snapshots.findByCaseIdAndWeek(UUID.fromString(o.caseId()), 2).orElseThrow().isNoChange());
        assertEquals(2, caches.findByCaseId(UUID.fromString(o.caseId())).orElseThrow().getWeek());
        assertTrue(json(mapper, mvc.perform(getMe(o.token(), "/me")).andReturn()).get("recordedThisWeek").asBoolean());
    }

    @Test
    void changedItemIsConfirmedOthersCarried() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        Map<String, Object> changed = Map.of("toilet", item(3, null, 2, null, "이제 혼자 가심"));
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, changed, Map.of("STANDING", java.util.List.of("GRIMACE")))))
            .andExpect(status().isOk());
        SnapshotBody b = body(o.caseId(), 2);
        assertEquals("CONFIRMED", b.items().get("toilet").level().source());
        assertEquals(3, b.items().get("toilet").level().value());
        assertEquals("이제 혼자 가심", b.items().get("toilet").note());
        assertEquals("CARRIED", b.items().get("feeding").level().source());
        assertEquals(1, b.items().get("feeding").hand().value());
        assertEquals(java.util.List.of("GRIMACE"), b.painSignal().get("STANDING"));
    }

    @Test
    void rejectsAxisNotApplicableAndMissingSignals() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of("toilet", item(3, null, 2, 1, null)), Map.of())))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("HAND")));
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of("toilet", item(3, null, 2, null, null)), null)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("painSignal")));
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of(), Map.of())))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(true, Map.of("toilet", item(3, null, 2, null, null)), Map.of())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void fullRecheckWeekNeedsAllEightItems() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(21);
        assertEquals(4, json(mapper, mvc.perform(getMe(o.token(), "/me")).andReturn()).get("week").asInt());
        mvc.perform(putJson(o.token(), "/me/weeks/4", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("전체 재확인")));
        mvc.perform(putJson(o.token(), "/me/weeks/4", mapper, weekly(false, Map.of("toilet", item(3, null, 2, null, null)), Map.of())))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/weeks/4", mapper, weekly(false, baselineItems(true), Map.of())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("FULL_RECHECK"));
        SnapshotBody b = body(o.caseId(), 4);
        for (var e : b.items().entrySet()) {
            assertEquals("CONFIRMED", e.getValue().level().source(), e.getKey());
        }
    }

    @Test
    void sameWeekOverwrites() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of("toilet", item(3, null, 2, null, null)), Map.of())))
            .andExpect(status().isOk());
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of("toilet", item(1, null, 2, null, null)), Map.of())))
            .andExpect(status().isOk());
        assertEquals(1, body(o.caseId(), 2).items().get("toilet").level().value());
        assertEquals(2, snapshots.findByCaseIdOrderByWeekAsc(UUID.fromString(o.caseId())).size());
    }
    private static Map<String, Object> v2(String question, String value) {
        return Map.of("questionnaireVersion", 2, "answers", Map.of(question, java.util.List.of(value)));
    }

    private static Map<String, Object> revisedItems() {
        Map<String, Object> items = baselineItems(true);
        items.put("toilet", Map.of("questionnaireVersion", 2, "answers", Map.of(
            "transfer", java.util.List.of("4"), "clothing", java.util.List.of("3"), "hygiene", java.util.List.of("2"))));
        items.put("dressing", v2("assistance", "4"));
        items.put("grooming", Map.of("questionnaireVersion", 2, "answers", Map.of(
            "washing", java.util.List.of("unknown"), "brushing", java.util.List.of("not_performed"))));
        items.put("feeding", v2("route", "tube"));
        return items;
    }

    @Test
    void upgradeFlagUsesLatestSnapshotAndNewAnswersSurviveNoChange() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        mvc.perform(getMe(o.token(), "/me")).andExpect(jsonPath("$.questionnaireUpgradeRequired").value(true));
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, revisedItems(), Map.of())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questionsRefreshed").value(true));
        mvc.perform(getMe(o.token(), "/me")).andExpect(jsonPath("$.questionnaireUpgradeRequired").value(false));
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/3", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isOk());
        var carried = mapper.valueToTree(body(o.caseId(), 3));
        assertEquals(2, carried.path("items").path("feeding").path("questionnaireVersion").asInt());
        assertEquals("CARRIED", carried.path("items").path("feeding").path("answerSource").asText());
        assertEquals("tube", carried.path("items").path("feeding").path("answers").path("route").get(0).asText());
        mvc.perform(getMe(o.token(), "/me/trajectory")).andExpect(status().isOk());
        mvc.perform(getMe(o.token(), "/me/progress")).andExpect(status().isOk());
    }

    @Test
    void sameWeekLegacyWriteCannotDowngradeAnUpgradedCategory() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of("dressing", v2("assistance", "4")), Map.of())))
            .andExpect(status().isOk());
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of("dressing", item(2, null, 2, 1, null)), Map.of())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void sameWeekNoChangeCannotRestoreOldSchemaFromPriorWeek() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, revisedItems(), Map.of())))
            .andExpect(status().isOk());
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isOk());
        mvc.perform(getMe(o.token(), "/me")).andExpect(jsonPath("$.questionnaireUpgradeRequired").value(false));
        assertEquals(2, mapper.valueToTree(body(o.caseId(), 2)).path("items").path("dressing").path("questionnaireVersion").asInt());
    }

    @Test
    void sameWeekCorrectionPreservesOmittedConfirmedAnswersAndNoteUntilNextWeek() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        var dressing = Map.of("questionnaireVersion", 2, "answers", Map.of("assistance", java.util.List.of("3")),
            "note", "단추를 도와드림");
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(false, Map.of("dressing", dressing), Map.of())))
            .andExpect(status().isOk());
        var confirmed = body(o.caseId(), 2).items().get("dressing");
        assertEquals("CONFIRMED", confirmed.answerSource());
        assertEquals("단추를 도와드림", confirmed.note());
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper,
            weekly(false, Map.of("bathing", baselineItems(true).get("bathing")), Map.of())))
            .andExpect(status().isOk());
        assertEquals(confirmed, body(o.caseId(), 2).items().get("dressing"));
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isOk());
        assertEquals(confirmed, body(o.caseId(), 2).items().get("dressing"));
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/3", mapper, weekly(true, Map.of(), Map.of())))
            .andExpect(status().isOk());
        var carried = body(o.caseId(), 3).items().get("dressing");
        assertEquals(confirmed.answers(), carried.answers());
        assertEquals("CARRIED", carried.answerSource());
        assertNull(carried.note());
    }

}
