package nextvisit.api.demo;

import static nextvisit.api.ApiTestSupport.getMe;
import static nextvisit.api.ApiTestSupport.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** README §10 데모 시나리오를 HTTP로 끝까지. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfig.DEFAULT_TODAY);
    }

    @Test
    void demoProducesTheThreeReadmeQuestionsAndTherapistSummary() throws Exception {
        JsonNode demo = json(mapper, mvc.perform(post("/demo")).andExpect(status().isCreated()).andReturn());
        String token = demo.get("guardianToken").asText();
        assertEquals(8, demo.get("recoveryCode").asText().length());
        assertTrue(demo.get("therapistUrl").asText().startsWith("/t/"));

        JsonNode me = json(mapper, mvc.perform(getMe(token, "/me")).andExpect(status().isOk()).andReturn());
        assertEquals(6, me.get("week").asInt());
        assertTrue(me.get("recordedThisWeek").asBoolean());
        assertEquals("2026-09-08", me.get("nextVisitDate").asText());

        JsonNode card = json(mapper, mvc.perform(getMe(token, "/me/prep-card")).andExpect(status().isOk()).andReturn());
        assertEquals(3, card.get("questions").size());
        assertEquals("화장실 이용은 혼자 하심으로 바뀌셨는데 집 안에서 걷기는 6주째 그대로입니다. 집 안에서 걷기는 왜 안 늘고 있을까요?", card.get("questions").get(0).get("sentence").asText());
        assertEquals("일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", card.get("questions").get(1).get("sentence").asText());
        assertEquals("식사는 혼자 하심으로 바뀌셨는데 마비된 손은 안 씀으로 바뀌었습니다. 괜찮은 걸까요?", card.get("questions").get(2).get("sentence").asText());
        assertEquals("TEMPLATE", card.get("questions").get(0).get("source").asText());

        JsonNode progress = json(mapper, mvc.perform(getMe(token, "/me/progress")).andReturn());
        assertFalse(progress.get("silent").asBoolean());

        JsonNode summary = json(mapper, mvc.perform(get(demo.get("therapistUrl").asText())).andExpect(status().isOk()).andReturn());
        assertEquals(6, summary.get("weeks").size());
        assertEquals(3, summary.get("freeNotes").size());
        assertEquals("낮잠 자고 일어나면 어깨 쪽을 감싸신다", summary.get("freeNotes").get(2).get("text").asText());

        JsonNode second = json(mapper, mvc.perform(post("/demo")).andReturn());
        assertFalse(second.get("caseId").asText().equals(demo.get("caseId").asText()), "매번 새 케이스");
    }

    @Test
    void catalogExposesSetLabelsAndEnums() throws Exception {
        JsonNode c = json(mapper, mvc.perform(get("/catalog")).andExpect(status().isOk()).andReturn());
        assertEquals("stroke", c.get("set").asText());
        assertEquals(8, c.get("items").size());
        assertEquals("transfer", c.get("items").get(0).get("code").asText());
        assertEquals("옮겨 앉기", c.get("items").get(0).get("phrase").asText());
        assertEquals(4, c.get("axes").get("LEVEL").size());
        assertEquals("혼자 하심", c.get("axes").get("LEVEL").get(3).get("label").asText());
        assertEquals(5, c.get("axes").get("AID").size());
        assertEquals(6, c.get("signalActions").size());
        assertEquals("찡그림", c.get("signalKinds").get(0).get("label").asText());
        assertEquals(4, c.get("timeTags").size());
    }
}
