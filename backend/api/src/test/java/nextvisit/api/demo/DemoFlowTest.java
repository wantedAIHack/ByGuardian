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
import java.util.ArrayList;
import java.util.List;
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
        assertTrue(demo.hasNonNull("therapistToken"));
        String therapistToken = demo.get("therapistToken").asText();
        assertTrue(therapistToken.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals("/t/" + therapistToken, demo.get("therapistUrl").asText());

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

        // 아래 값들은 frontend/src/test/fixtures.ts 의 catalogFixture 와 한 글자도 다르면 안 된다.
        // 프론트 화면 테스트 여섯 개가 이 값들 위에 서 있고, 어긋나면 존재하지 않는 것을 검사하게 된다.
        String[][] items = {
            {"transfer",   "침대·의자에서 옮겨 앉기", "옮겨 앉기",     "mobility", "LEVEL,AID,CONSISTENCY"},
            {"ambulation", "집 안에서 걷기",          "집 안에서 걷기", "mobility", "LEVEL,AID,CONSISTENCY"},
            {"stairs",     "문턱·계단",               "문턱·계단",      "mobility", "LEVEL,AID,CONSISTENCY"},
            {"toilet",     "화장실 이용",             "화장실 이용",    "selfcare", "LEVEL,CONSISTENCY"},
            {"dressing",   "옷 입기",                 "옷 입기",        "selfcare", "LEVEL,CONSISTENCY,HAND"},
            {"grooming",   "세수·양치",               "세수·양치",      "selfcare", "LEVEL,CONSISTENCY,HAND"},
            {"bathing",    "목욕",                    "목욕",           "selfcare", "LEVEL,CONSISTENCY"},
            {"feeding",    "식사",                    "식사",           "selfcare", "LEVEL,CONSISTENCY,HAND"},
        };
        assertEquals(items.length, c.get("items").size());
        for (int i = 0; i < items.length; i++) {
            JsonNode it = c.get("items").get(i);
            assertEquals(items[i][0], it.get("code").asText(), "items[" + i + "].code");
            assertEquals(items[i][1], it.get("label").asText(), "items[" + i + "].label");
            assertEquals(items[i][2], it.get("phrase").asText(), "items[" + i + "].phrase");
            assertEquals(items[i][3], it.get("group").asText(), "items[" + i + "].group");
            List<String> axes = new ArrayList<>();
            it.get("axes").forEach(a -> axes.add(a.asText()));
            assertEquals(items[i][4], String.join(",", axes), "items[" + i + "].axes");
        }

        assertAxis(c, "LEVEL", "대부분 도움", "손 잡아드림", "지켜보면 됨", "혼자 하심");
        assertAxis(c, "AID", "휠체어", "워커", "지팡이", "가구·난간 잡음", "아무것도 안 잡음");
        assertAxis(c, "CONSISTENCY", "좋은 날만", "대체로", "매번");
        assertAxis(c, "HAND", "안 씀", "거들기만", "주로 씀");

        assertCodeLabels(c, "signalActions",
            "STANDING", "일어설 때", "WALKING", "걸을 때", "TRANSFER", "옮겨 앉을 때",
            "DRESSING", "옷 입을 때", "WASHING", "세수할 때", "EATING", "식사할 때");
        assertCodeLabels(c, "signalKinds",
            "GRIMACE", "찡그림", "VOCAL", "소리 냄", "GUARDING", "팔을 감싸거나 피함");
        assertCodeLabels(c, "timeTags",
            "MORNING", "오전", "AFTERNOON", "오후", "EVENING", "저녁", "ANY", "상관없음");
        assertCodeLabels(c, "sleepLevels",
            "0", "자주 깨심", "1", "가끔 깨심", "2", "잘 주무심");
        assertCodeLabels(c, "axisLabels",
            "LEVEL", "도움 수준", "AID", "보조 도구",
            "CONSISTENCY", "이번 주 빈도", "HAND", "마비 쪽 손");
        // 헤더 질문은 인라인 라벨과 다른 문자열이어야 한다. 같아지면 홈·준비 카드·
        // 치료사 표에 "문턱·계단 · 이번 주에 얼마나 자주 그러셨나요?"가 다시 나온다.
        assertCodeLabels(c, "axisQuestions",
            "LEVEL", "도움 수준", "AID", "보조 도구",
            "CONSISTENCY", "이번 주에 얼마나 자주 그러셨나요?", "HAND", "마비 쪽 손");
    }

    private static void assertAxis(JsonNode c, String axis, String... labels) {
        JsonNode vals = c.get("axes").get(axis);
        assertEquals(labels.length, vals.size(), axis + " 값 개수");
        for (int i = 0; i < labels.length; i++) {
            assertEquals(i, vals.get(i).get("value").asInt(), axis + "[" + i + "].value");
            assertEquals(labels[i], vals.get(i).get("label").asText(), axis + "[" + i + "].label");
        }
    }

    /** codeLabel 쌍을 순서까지 확인한다. 프론트가 배열 순서 그대로 화면에 낸다. */
    private static void assertCodeLabels(JsonNode c, String field, String... codeThenLabel) {
        JsonNode arr = c.get(field);
        assertEquals(codeThenLabel.length / 2, arr.size(), field + " 개수");
        for (int i = 0; i < arr.size(); i++) {
            assertEquals(codeThenLabel[i * 2], arr.get(i).get("code").asText(), field + "[" + i + "].code");
            assertEquals(codeThenLabel[i * 2 + 1], arr.get(i).get("label").asText(), field + "[" + i + "].label");
        }
    }
}
