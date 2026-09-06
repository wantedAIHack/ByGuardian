package nextvisit.api.therapist;

import static nextvisit.api.ApiTestSupport.authed;
import static nextvisit.api.ApiTestSupport.json;
import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.postJson;
import static nextvisit.api.ApiTestSupport.putJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nextvisit.api.ApiTestSupport.Onboarded;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.demo.DemoSeedWriter;
import nextvisit.api.questions.QuestionService;
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
class TherapistControllerTest {

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

    private String issue(String token) throws Exception {
        JsonNode n = json(mapper, mvc.perform(authed(post("/me/therapist-link"), token)).andExpect(status().isOk()).andReturn());
        assertEquals("/t/" + n.get("token").asText(), n.get("url").asText());
        return n.get("token").asText();
    }

    @Test
    void summaryHasValuesVerbatimNotesAndNoJudgment() throws Exception {
        Onboarded o = seeded();
        String t = issue(o.token());
        var r = mvc.perform(get("/t/" + t)).andExpect(status().isOk()).andReturn();
        String raw = r.getResponse().getContentAsString();
        JsonNode s = mapper.readTree(raw);

        assertEquals(List.of(1, 2, 3, 4, 5, 6), mapper.convertValue(s.get("weeks"), List.class));
        assertEquals(8, s.get("items").size());
        assertTrue(s.get("signalsEnabled").asBoolean());
        assertEquals(2, s.get("signals").size());
        assertEquals("GRIMACE", s.get("signals").get(0).get("kind").asText());
        assertEquals(List.of(2, 3, 5, 6), mapper.convertValue(s.get("signals").get(0).get("weeks"), List.class));
        assertEquals(0, s.get("sleep").size());
        assertEquals(3, s.get("freeNotes").size());
        assertEquals("오후만 되면 오른쪽 어깨를 자꾸 만지신다", s.get("freeNotes").get(0).get("text").asText());
        assertEquals("오후", s.get("freeNotes").get(0).get("timeTagLabel").asText());
        assertEquals(3, s.get("questions").size());
        assertEquals("일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", s.get("questions").get(1).asText());
        JsonNode d = s.get("density");
        assertEquals(6, d.get("totalWeeks").asInt());
        assertEquals(6, d.get("recordedWeeks").asInt());
        assertEquals(5, d.get("confirmedWeeks").asInt());
        assertEquals(List.of("딸"), mapper.convertValue(d.get("authors"), List.class));
        assertEquals(0, s.get("authorChanges").size());
        assertEquals("이 기록은 보호자가 가정에서 관찰해 남긴 것입니다. 측정이나 평가가 아니며 검사 결과를 포함하지 않습니다.", s.get("disclaimer").asText());
        for (String word : List.of("SUSTAINED", "FLUCTUATING", "OBSERVED_ONCE", "\"status\"", "\"direction\"", "\"duration\"", "상승", "하락")) {
            assertFalse(raw.contains(word), word);
        }
    }

    @Test
    void authorChangeIsMarkedAtTheWeekItHappens() throws Exception {
        Onboarded o = seeded();
        JsonNode rec = json(mapper, mvc.perform(postJson("/guardians/recover", mapper, Map.of("recoveryCode", o.recoveryCode(), "relation", "아들"))).andReturn());
        String son = rec.get("guardianToken").asText();
        clock.advanceDays(7);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("noChange", true);
        body.put("changedItems", Map.of());
        body.put("painSignal", Map.of());
        body.put("sleep", null);
        body.put("freeNote", null);
        mvc.perform(putJson(son, "/me/weeks/7", mapper, body)).andExpect(status().isOk());

        String t = issue(o.token());
        JsonNode s = json(mapper, mvc.perform(get("/t/" + t)).andReturn());
        assertEquals(1, s.get("authorChanges").size());
        assertEquals(7, s.get("authorChanges").get(0).get("week").asInt());
        assertEquals("딸", s.get("authorChanges").get(0).get("from").asText());
        assertEquals("아들", s.get("authorChanges").get(0).get("to").asText());
        assertEquals(List.of("딸", "아들"), mapper.convertValue(s.get("density").get("authors"), List.class));
        assertEquals(7, s.get("density").get("totalWeeks").asInt());
        assertEquals(7, s.get("density").get("recordedWeeks").asInt());
    }

    @Test
    void reissueRevokesPreviousLink() throws Exception {
        Onboarded o = seeded();
        String first = issue(o.token());
        String second = issue(o.token());
        assertNotEquals(first, second);
        mvc.perform(get("/t/" + first)).andExpect(status().isNotFound());
        mvc.perform(get("/t/" + second)).andExpect(status().isOk());
        mvc.perform(get("/t/nope")).andExpect(status().isNotFound());
    }

    @Test
    void catalogReturnsThreeSleepLevelsWithBestLast() throws Exception {
        JsonNode c = json(mapper, mvc.perform(get("/catalog")).andExpect(status().isOk()).andReturn());
        JsonNode levels = c.get("sleepLevels");
        assertEquals(3, levels.size());
        assertEquals("자주 깨심", levels.get(0).get("label").asText());
        assertEquals("잘 주무심", levels.get(2).get("label").asText());
    }

    @Test
    void totalWeeksDoesNotCountAnUnrecordedWeekInProgress() throws Exception {
        Onboarded o = seeded();
        clock.advanceDays(7);   // 7주차, 아직 기록 없음
        String t = issue(o.token());
        JsonNode s = json(mapper, mvc.perform(get("/t/" + t)).andReturn());
        assertEquals(6, s.get("density").get("totalWeeks").asInt());
    }
}
