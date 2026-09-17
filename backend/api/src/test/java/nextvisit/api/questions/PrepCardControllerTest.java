package nextvisit.api.questions;

import static nextvisit.api.ApiTestSupport.getMe;
import static nextvisit.api.ApiTestSupport.json;
import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.putJson;
import static nextvisit.api.ApiTestSupport.authed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    private List<Map<String, Object>> saveBody(Object... idSentencePairs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < idSentencePairs.length; i += 2) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", idSentencePairs[i]);
            item.put("sentence", idSentencePairs[i + 1]);
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> noChangeWeek() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("noChange", true);
        body.put("changedItems", Map.of());
        body.put("painSignal", Map.of());
        body.put("sleep", 1);
        body.put("freeNote", null);
        return body;
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
        assertEquals("집 안에서 걷기: 보조 도구: 워커 → 지팡이 (5주차부터)", glance.get(0).asText());
        assertEquals("화장실 이용: 지켜보면 됨 → 혼자 하심 (3주차부터)", glance.get(1).asText());
        assertEquals("식사: 지켜보면 됨 → 혼자 하심 (4주차부터), 마비 쪽 손: 거들기만 → 안 씀 (4주차부터)", glance.get(2).asText());
        assertEquals("침대·의자에서 옮겨 앉기: 손 잡아드림 → 지켜보면 됨 (4주차부터)", glance.get(3).asText());
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

    @Test
    void cardExposesItemsWithBasisAndFlags() throws Exception {
        Onboarded o = seeded();
        JsonNode card = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andExpect(status().isOk()).andReturn());

        assertEquals("TEMPLATE_ONLY", card.get("generationStatus").asText());
        assertFalse(card.get("edited").asBoolean());
        assertFalse(card.get("suggestionAvailable").asBoolean());
        JsonNode items = card.get("items");
        assertEquals(3, items.size());
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertTrue(items.get(0).get("id").asText().startsWith("q1-"));
        assertEquals(card.get("questions").get(0).get("sentence").asText(), items.get(0).get("sentence").asText());
        assertTrue(items.get(0).get("basis").get("evidence").get("items").size() > 0);
        assertEquals(0, items.get(0).get("basis").get("notes").size());
        assertTrue(card.get("questions").get(0).has("evidence"));
        assertTrue(card.has("extraQuestions"));
    }

    @Test
    void legacyExtraQuestionsAppearAsCaregiverItems() throws Exception {
        Onboarded o = seeded();
        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("밤에 자주 깨시는데 괜찮을까요?"))))
            .andExpect(status().isOk());
        JsonNode items = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");

        assertEquals(4, items.size());
        assertEquals("CAREGIVER", items.get(3).get("origin").asText());
        assertEquals("밤에 자주 깨시는데 괜찮을까요?", items.get(3).get("sentence").asText());
        assertTrue(items.get(3).get("id").asText().startsWith("x1-"));
    }

    @Test
    void savingKeepsServerBasisForKnownIdsAndMarksEdits() throws Exception {
        Onboarded o = seeded();
        JsonNode before = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");
        String firstId = before.get(0).get("id").asText();

        JsonNode card = json(mapper, mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(firstId, "고친 첫 질문인데 괜찮을까요?", null, "직접 적은 질문인데 괜찮을까요?",
                "q9-unknown", "모르는 id 질문인데 괜찮을까요?")))).andExpect(status().isOk()).andReturn());

        assertTrue(card.get("edited").asBoolean());
        JsonNode items = card.get("items");
        assertEquals(3, items.size());
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertTrue(items.get(0).get("edited").asBoolean());
        assertEquals(before.get(0).get("basis").get("evidence"), items.get(0).get("basis").get("evidence"));
        assertEquals("CAREGIVER", items.get(1).get("origin").asText());
        assertEquals("CAREGIVER", items.get(2).get("origin").asText());
        assertEquals(0, items.get(2).get("basis").get("evidence").get("items").size());
        assertEquals(0, card.get("extraQuestions").size());
    }

    @Test
    void savingValidatesCountBlankLengthNullItemsAndDuplicateIds() throws Exception {
        Onboarded o = seeded();
        List<Object> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) { nine.add(null); nine.add("질문 " + i + " 괜찮을까요?"); }
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items", saveBody(nine.toArray()))))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items", saveBody(null, "   "))))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items", saveBody(null, "가".repeat(201)))))
            .andExpect(status().isBadRequest());
        List<Object> nullItem = new ArrayList<>();
        nullItem.add(null);
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items", nullItem)))
            .andExpect(status().isBadRequest());
        String knownId = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn())
            .get("items").get(0).get("id").asText();
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(knownId, "첫 질문", knownId, "두 번째 질문"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void confirmedListSurvivesANewWeekAndRegenerateRestoresTheSuggestion() throws Exception {
        Onboarded o = seeded();
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(null, "직접 적은 질문인데 괜찮을까요?")))).andExpect(status().isOk());

        clock.advanceSeconds(60);
        mvc.perform(putJson(o.token(), "/me/weeks/6", mapper, noChangeWeek())).andExpect(status().isOk());

        JsonNode after = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn());
        assertEquals(1, after.get("items").size());
        assertEquals("직접 적은 질문인데 괜찮을까요?", after.get("items").get(0).get("sentence").asText());
        assertTrue(after.get("suggestionAvailable").asBoolean());

        JsonNode regenerated = json(mapper, mvc.perform(authed(post("/me/prep-card/regenerate"), o.token()))
            .andExpect(status().isOk()).andReturn());
        assertFalse(regenerated.get("edited").asBoolean());
        assertFalse(regenerated.get("suggestionAvailable").asBoolean());
        JsonNode items = regenerated.get("items");
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertEquals("직접 적은 질문인데 괜찮을까요?", items.get(items.size() - 1).get("sentence").asText());
        assertEquals("CAREGIVER", items.get(items.size() - 1).get("origin").asText());
    }

    @Test
    void extraEndpointPreservesRetainedIdentityAndReplacesCaregiverItemsWhenAListIsConfirmed() throws Exception {
        Onboarded o = seeded();
        JsonNode before = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");
        JsonNode confirmed = json(mapper, mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items",
            saveBody(before.get(0).get("id").asText(), before.get(0).get("sentence").asText(), null, "옛 질문인데 괜찮을까요?"))))
            .andExpect(status().isOk()).andReturn()).get("items");
        String retainedId = confirmed.get(0).get("id").asText();
        String removedCaregiverId = confirmed.get(1).get("id").asText();
        assertTrue(retainedId.startsWith("c-"));
        assertTrue(removedCaregiverId.startsWith("c-"));

        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("새 질문인데 괜찮을까요?"))))
            .andExpect(status().isOk());

        JsonNode items = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");
        assertEquals(2, items.size());
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertEquals("새 질문인데 괜찮을까요?", items.get(1).get("sentence").asText());
        assertEquals(retainedId, items.get(0).get("id").asText());
        assertTrue(items.get(1).get("id").asText().startsWith("c-"));
        assertFalse(removedCaregiverId.equals(items.get(1).get("id").asText()));
    }

    @Test
    void removedConfirmedIdCannotInheritBasisAfterReorder() throws Exception {
        Onboarded o = seeded();
        JsonNode before = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");
        JsonNode confirmed = json(mapper, mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(before.get(0).get("id").asText(), before.get(0).get("sentence").asText(),
                before.get(1).get("id").asText(), before.get(1).get("sentence").asText()))))
            .andExpect(status().isOk()).andReturn()).get("items");
        String removedId = confirmed.get(0).get("id").asText();
        String retainedId = confirmed.get(1).get("id").asText();

        JsonNode reordered = json(mapper, mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(retainedId, confirmed.get(1).get("sentence").asText()))))
            .andExpect(status().isOk()).andReturn()).get("items");
        assertEquals(retainedId, reordered.get(0).get("id").asText());

        JsonNode stale = json(mapper, mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(removedId, "오래된 질문인데 괜찮을까요?"))))
            .andExpect(status().isOk()).andReturn()).get("items").get(0);
        assertEquals("CAREGIVER", stale.get("origin").asText());
        assertTrue(stale.get("id").asText().startsWith("c-"));
        assertFalse(removedId.equals(stale.get("id").asText()));
        assertEquals(0, stale.get("basis").get("evidence").get("items").size());
    }

    @Test
    void extraEndpointRejectsACombinedConfirmedListOverEightItems() throws Exception {
        Onboarded o = seeded();
        List<Map<String, Object>> confirmed = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "c" + i);
            item.put("sentence", "기존 질문 " + i);
            item.put("origin", "TEMPLATE");
            item.put("edited", false);
            item.put("type", "AID_CHANGE");
            item.put("items", List.of());
            item.put("signal", null);
            item.put("basis", Map.of("detections", List.of(), "noteWeeks", List.of()));
            confirmed.add(item);
        }
        var kase = cases.findById(UUID.fromString(o.caseId())).orElseThrow();
        kase.confirmQuestions(mapper.writeValueAsString(confirmed), clock.instant());
        cases.saveAndFlush(kase);

        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("추가 질문"))))
            .andExpect(status().isBadRequest());
    }
}
