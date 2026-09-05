package nextvisit.api.questions;

import static nextvisit.api.ApiTestSupport.getMe;
import static nextvisit.api.ApiTestSupport.json;
import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.putJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nextvisit.api.ApiTestSupport.Onboarded;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.demo.DemoSeedWriter;
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
class PrepCardControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @Autowired CaseRepository cases;
    @Autowired GuardianRepository guardians;
    @Autowired DemoSeedWriter writer;
    @Autowired QuestionService questions;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfig.DEFAULT_TODAY);
    }

    private Onboarded seeded() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        UUID id = UUID.fromString(o.caseId());
        writer.write(cases.findById(id).orElseThrow(), guardians.findByCaseId(id).get(0));
        questions.refresh(id);
        clock.advanceDays(35);
        return o;
    }

    @Test
    void seedCardHasThreeQuestionsWithEvidenceAndGlance() throws Exception {
        Onboarded o = seeded();
        JsonNode card = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andExpect(status().isOk()).andReturn());
        assertEquals(6, card.get("week").asInt());
        assertEquals("2026-09-30", card.get("nextVisitDate").asText());
        assertTrue(card.get("emptyMessage").isNull());
        assertEquals(0, card.get("extraQuestions").size());

        JsonNode qs = card.get("questions");
        assertEquals(3, qs.size());
        assertEquals("일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", qs.get(1).get("sentence").asText());
        assertEquals("TEMPLATE", qs.get(1).get("source").asText());

        JsonNode e0 = qs.get(0).get("evidence");
        assertEquals(2, e0.get("items").size());
        assertEquals("toilet", e0.get("items").get(0).get("code").asText());
        assertEquals("LEVEL", e0.get("items").get(0).get("axis").asText());
        assertEquals(6, e0.get("items").get(0).get("values").size());
        assertEquals("ambulation", e0.get("items").get(1).get("code").asText());
        assertTrue(e0.get("signal").isNull());

        JsonNode s1 = qs.get(1).get("evidence").get("signal");
        assertEquals("STANDING", s1.get("action").asText());
        assertEquals("일어설 때", s1.get("actionLabel").asText());
        assertEquals("GRIMACE", s1.get("kind").asText());
        assertEquals("찡그림", s1.get("kindLabel").asText());
        assertEquals(List.of(3, 5, 6), mapper.convertValue(s1.get("weeks"), List.class));
        assertEquals(4, s1.get("window").asInt());

        JsonNode e2 = qs.get(2).get("evidence");
        assertEquals("feeding", e2.get("items").get(0).get("code").asText());
        assertEquals("LEVEL", e2.get("items").get(0).get("axis").asText());
        assertEquals("HAND", e2.get("items").get(1).get("axis").asText());

        JsonNode glance = card.get("therapistGlance");
        assertEquals(4, glance.size());
        assertEquals("침대·의자에서 옮겨 앉기: 손 잡아드림 → 지켜보면 됨 (4주차부터)", glance.get(0).asText());
        assertEquals("집 안에서 걷기: 보조 도구: 워커 → 지팡이 (5주차부터)", glance.get(1).asText());
        assertEquals("문턱·계단: 한 주 일관성: 매번 → 좋은 날만 (3주차부터)", glance.get(2).asText());
        assertEquals("화장실 이용: 지켜보면 됨 → 혼자 하심 (3주차부터)", glance.get(3).asText());
        for (JsonNode g : glance) {
            assertFalse(g.asText().contains("SUSTAINED"));
            assertFalse(g.asText().contains("상승"));
        }
    }

    @Test
    void baselineOnlyCardIsEmptyWithMessage() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        JsonNode card = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andExpect(status().isOk()).andReturn());
        assertEquals(0, card.get("questions").size());
        assertEquals("이번 기간 관찰에서 달라진 것이 없었습니다", card.get("emptyMessage").asText());
        assertEquals(0, card.get("therapistGlance").size());
    }

    @Test
    void extraQuestionsRoundTripAndValidate() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("약은 지금처럼 계속 드려도 될까요?"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("약은 지금처럼 계속 드려도 될까요?"));
        JsonNode card = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn());
        assertEquals("약은 지금처럼 계속 드려도 될까요?", card.get("extraQuestions").get(0).asText());

        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("a", "b", "c", "d", "e", "f"))))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("x".repeat(201)))))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of())))
            .andExpect(status().isOk());
        assertNull(json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("extraQuestions").get(0));
    }
}
