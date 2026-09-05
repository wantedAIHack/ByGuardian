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
}
