package nextvisit.api.cases;

import static nextvisit.api.ApiTestSupport.baselineItems;
import static nextvisit.api.ApiTestSupport.getMe;
import static nextvisit.api.ApiTestSupport.json;
import static nextvisit.api.ApiTestSupport.onboard;
import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.onboardingBody;
import static nextvisit.api.ApiTestSupport.postJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import nextvisit.api.ApiTestSupport.Onboarded;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotKind;
import nextvisit.api.snapshots.SnapshotRepository;
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
class CaseControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @Autowired SnapshotRepository snapshots;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfig.DEFAULT_TODAY);
    }

    @Test
    void onboardingCreatesCaseGuardianAndBaseline() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        assertEquals(8, o.recoveryCode().length());
        UUID.fromString(o.token());

        Snapshot base = snapshots.findByCaseIdAndWeek(UUID.fromString(o.caseId()), 1).orElseThrow();
        assertEquals(SnapshotKind.BASELINE, base.getKind());
        assertFalse(base.isNoChange());
        SnapshotBody body = mapper.readValue(base.getBody(), SnapshotBody.class);
        assertEquals(8, body.items().size());
        assertEquals("CONFIRMED", body.items().get("toilet").level().source());
        assertEquals(1, body.items().get("feeding").hand().value());
        assertEquals(1, body.items().get("ambulation").aid().value());
        assertTrue(body.items().get("toilet").aid() == null);
        assertTrue(body.painSignal().isEmpty());
    }

    @Test
    void onboardingRejectsMissingItem() throws Exception {
        Map<String, Object> items = baselineItems(true);
        items.remove("bathing");
        mvc.perform(postJson("/cases", mapper, onboardingBody("딸", "RIGHT", "OFTEN", items, Map.of())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION"))
            .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("bathing")));
    }

    @Test
    void onboardingRejectsHandWhenPareticSideUnknown() throws Exception {
        Map<String, Object> items = baselineItems(true);   // hand=1 on hand items
        mvc.perform(postJson("/cases", mapper, onboardingBody("딸", "UNKNOWN", "OFTEN", items, Map.of())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("HAND")));
    }

    @Test
    void onboardingRequiresPainSignalWhenSignalsEnabled() throws Exception {
        mvc.perform(postJson("/cases", mapper, onboardingBody("딸", "RIGHT", "OFTEN", baselineItems(true), null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("painSignal")));
        mvc.perform(postJson("/cases", mapper, onboardingBody("딸", "RIGHT", "NONE", baselineItems(true), null)))
            .andExpect(status().isCreated());
    }

    @Test
    void meRequiresValidToken() throws Exception {
        mvc.perform(get("/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(getMe("not-a-token", "/me")).andExpect(status().isUnauthorized());

        Onboarded o = onboardDefault(mvc, mapper);
        JsonNode me = json(mapper, mvc.perform(getMe(o.token(), "/me")).andExpect(status().isOk()).andReturn());
        assertEquals(1, me.get("week").asInt());
        assertEquals("딸", me.get("relation").asText());
        assertFalse(me.get("canRecordThisWeek").asBoolean());
        assertTrue(me.get("recordedThisWeek").asBoolean());
        assertEquals(1, me.get("lastRecordedWeek").asInt());
        assertTrue(me.get("signalsEnabled").asBoolean());
        assertTrue(me.get("handEnabled").asBoolean());
        assertFalse(me.get("fullRecheck").asBoolean());
        assertEquals("2026-09-05", me.get("today").asText());
    }

    @Test
    void meMovesToWeekTwoAfterSevenDays() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        JsonNode me = json(mapper, mvc.perform(getMe(o.token(), "/me")).andReturn());
        assertEquals(2, me.get("week").asInt());
        assertTrue(me.get("canRecordThisWeek").asBoolean());
        assertFalse(me.get("recordedThisWeek").asBoolean());
        clock.advanceDays(14);
        assertTrue(json(mapper, mvc.perform(getMe(o.token(), "/me")).andReturn()).get("fullRecheck").asBoolean());
    }

    @Test
    void recoverIssuesSecondGuardianOnSameCase() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        JsonNode r = json(mapper, mvc.perform(postJson("/guardians/recover", mapper, Map.of("recoveryCode", o.recoveryCode(), "relation", "아들")))
            .andExpect(status().isOk()).andReturn());
        String token2 = r.get("guardianToken").asText();
        assertEquals(o.caseId(), r.get("caseId").asText());
        JsonNode me = json(mapper, mvc.perform(getMe(token2, "/me")).andExpect(status().isOk()).andReturn());
        assertEquals("아들", me.get("relation").asText());
        assertEquals(o.caseId(), me.get("caseId").asText());

        mvc.perform(postJson("/guardians/recover", mapper, Map.of("recoveryCode", "ZZZZZZZZ", "relation", "아들")))
            .andExpect(status().isNotFound());
    }
}
