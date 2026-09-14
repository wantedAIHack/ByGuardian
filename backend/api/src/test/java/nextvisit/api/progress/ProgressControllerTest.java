package nextvisit.api.progress;

import static nextvisit.api.ApiTestSupport.getMe;
import static nextvisit.api.ApiTestSupport.json;
import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.putJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
import nextvisit.engine.Templates;
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
class ProgressControllerTest {

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

    private static Map<String, Object> weekly(boolean noChange, Map<String, Object> changed) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("noChange", noChange);
        b.put("changedItems", changed);
        b.put("painSignal", Map.of());
        b.put("sleep", null);
        b.put("freeNote", null);
        return b;
    }

    private static Map<String, Object> toilet(int level) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("level", level);
        v.put("aid", null);
        v.put("consistency", 2);
        v.put("hand", null);
        v.put("note", null);
        return Map.of("toilet", v);
    }

    private static List<String> messages(JsonNode progress) {
        List<String> out = new ArrayList<>();
        progress.get("changes").forEach(c -> out.add(c.get("message").asText()));
        progress.get("transitions").forEach(t -> out.add(t.get("message").asText()));
        return out;
    }

    @Test
    void seedShowsSustainedChangesHidesFluctuatingAndCarriesQuestions() throws Exception {
        Onboarded o = seeded();
        JsonNode p = json(mapper, mvc.perform(getMe(o.token(), "/me/progress")).andExpect(status().isOk()).andReturn());
        assertEquals(6, p.get("week").asInt());
        assertFalse(p.get("silent").asBoolean());

        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        p.get("changes").forEach(c -> byKey.put(c.get("item").asText() + "/" + c.get("axis").asText(), c));
        assertEquals("이 변화가 4주째 유지되고 있습니다.",
            byKey.get("toilet/LEVEL").get("message").asText());
        assertEquals("SUSTAINED", byKey.get("toilet/LEVEL").get("status").asText());
        assertEquals("이 변화가 2주째 유지되고 있습니다.",
            byKey.get("ambulation/AID").get("message").asText());
        assertEquals("이 변화가 3주째 유지되고 있습니다.",
            byKey.get("feeding/HAND").get("message").asText());
        assertFalse(byKey.containsKey("grooming/LEVEL"), "흔들림은 층 2에서 숨긴다");
        assertFalse(byKey.containsKey("bathing/LEVEL"), "변화 없음은 표시하지 않는다");
        assertEquals(0, p.get("transitions").size());
        assertEquals(3, p.get("questions").size());
        assertEquals("일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", p.get("questions").get(1).get("sentence").asText());
        assertTrue(p.get("questions").get(0).get("evidence") == null);
        for (String m : messages(p)) {
            assertFalse(Templates.containsForbiddenWord(m), m);
        }
    }

    @Test
    void transitionAppearsTheWeekSustainedTurnsFluctuatingThenDisappears() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);            // week 1: toilet 2
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(true, Map.of()))).andExpect(status().isOk());
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/3", mapper, weekly(false, toilet(3)))).andExpect(status().isOk());
        JsonNode w3 = json(mapper, mvc.perform(getMe(o.token(), "/me/progress")).andReturn());
        assertEquals("OBSERVED_ONCE", w3.get("changes").get(0).get("status").asText());
        assertEquals("한 번 달라진 것으로 관찰됐습니다. 아직 변화라고 보기 어렵습니다.", w3.get("changes").get(0).get("message").asText());

        clock.advanceDays(7);                                  // week 4 = full recheck
        mvc.perform(putJson(o.token(), "/me/weeks/4", mapper, weekly(false, fullItemsWithToilet(3)))).andExpect(status().isOk());
        JsonNode w4 = json(mapper, mvc.perform(getMe(o.token(), "/me/progress")).andReturn());
        assertEquals("SUSTAINED", w4.get("changes").get(0).get("status").asText());
        assertEquals(0, w4.get("transitions").size());

        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/5", mapper, weekly(false, toilet(2)))).andExpect(status().isOk());
        JsonNode w5 = json(mapper, mvc.perform(getMe(o.token(), "/me/progress")).andReturn());
        assertEquals(0, w5.get("changes").size(), "흔들림은 changes에 없다");
        assertEquals(1, w5.get("transitions").size());
        assertEquals("toilet", w5.get("transitions").get(0).get("item").asText());
        assertEquals("2주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다.",
            w5.get("transitions").get(0).get("message").asText());
        assertFalse(w5.get("silent").asBoolean());

        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/6", mapper, weekly(true, Map.of()))).andExpect(status().isOk());
        JsonNode w6 = json(mapper, mvc.perform(getMe(o.token(), "/me/progress")).andReturn());
        assertEquals(0, w6.get("transitions").size(), "다음 주에는 전환 문구가 사라진다");
        for (String m : messages(w5)) {
            assertFalse(Templates.containsForbiddenWord(m), m);
        }
    }

    private static Map<String, Object> fullItemsWithToilet(int toiletLevel) {
        Map<String, Object> items = nextvisit.api.ApiTestSupport.baselineItems(true);
        items.put("toilet", toilet(toiletLevel).get("toilet"));
        return items;
    }

    @Test
    void silentWhenNothingChanged() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        mvc.perform(putJson(o.token(), "/me/weeks/2", mapper, weekly(true, Map.of()))).andExpect(status().isOk());
        JsonNode p = json(mapper, mvc.perform(getMe(o.token(), "/me/progress")).andReturn());
        assertTrue(p.get("silent").asBoolean());
        assertEquals(0, p.get("changes").size());
        assertEquals(0, p.get("questions").size());
    }

    @Test
    void trajectoryHasValuesOnlyNoJudgment() throws Exception {
        Onboarded o = seeded();
        var r = mvc.perform(getMe(o.token(), "/me/trajectory")).andExpect(status().isOk()).andReturn();
        String raw = r.getResponse().getContentAsString();
        JsonNode t = mapper.readTree(raw);
        assertEquals(8, t.size());
        JsonNode toilet = t.get(3);
        assertEquals("toilet", toilet.get("code").asText());
        assertTrue(toilet.get("changed").asBoolean());
        assertEquals(6, toilet.get("axes").get(0).get("values").size());
        assertEquals("CARRIED", toilet.get("axes").get(0).get("values").get(1).get("source").asText());
        JsonNode ambulation = t.get(1);
        assertEquals("AID", ambulation.get("axes").get(1).get("axis").asText());
        assertEquals("지팡이", ambulation.get("axes").get(1).get("values").get(5).get("label").asText());
        assertFalse(t.get(6).get("changed").asBoolean(), "bathing");
        for (String word : List.of("SUSTAINED", "FLUCTUATING", "OBSERVED_ONCE", "\"status\"", "\"direction\"", "\"duration\"")) {
            assertFalse(raw.contains(word), word);
        }
    }
}
