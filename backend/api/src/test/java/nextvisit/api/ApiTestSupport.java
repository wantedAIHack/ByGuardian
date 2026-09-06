package nextvisit.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MockMvc 도우미. 온보딩 JSON을 만들고 토큰 헤더를 붙인다. */
public final class ApiTestSupport {

    public static final String HEADER = nextvisit.api.auth.GuardianAuthFilter.HEADER;

    private ApiTestSupport() {}

    public record Onboarded(String caseId, String token, String recoveryCode) {}

    /** 8항목 기준선. mobility는 aid 1, 모든 항목 consistency 2, hand는 handEnabled일 때만 1. */
    public static Map<String, Object> baselineItems(boolean handEnabled) {
        Map<String, Object> items = new LinkedHashMap<>();
        for (String code : List.of("transfer", "ambulation", "stairs", "toilet", "dressing", "grooming", "bathing", "feeding")) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("level", 2);
            boolean mobility = code.equals("transfer") || code.equals("ambulation") || code.equals("stairs");
            boolean handItem = code.equals("dressing") || code.equals("grooming") || code.equals("feeding");
            v.put("aid", mobility ? 1 : null);
            v.put("consistency", 2);
            v.put("hand", handItem && handEnabled ? 1 : null);
            v.put("note", null);
            items.put(code, v);
        }
        return items;
    }

    public static Map<String, Object> onboardingBody(String relation, String pareticSide, String verbalDifficulty,
                                                     Map<String, Object> items, Object painSignal) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("items", items);
        baseline.put("painSignal", painSignal);
        baseline.put("sleep", null);
        baseline.put("freeNote", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("relation", relation);
        body.put("diagnosis", "STROKE");
        body.put("pareticSide", pareticSide);
        body.put("verbalDifficulty", verbalDifficulty);
        body.put("nextVisitDate", "2026-09-30");
        body.put("baseline", baseline);
        return body;
    }

    /** 오른쪽 마비, 말로 표현 거의 어려움(신호 활성) 케이스를 온보딩한다. */
    public static Onboarded onboardDefault(MockMvc mvc, ObjectMapper mapper) throws Exception {
        Map<String, Object> body = onboardingBody("딸", "RIGHT", "OFTEN", baselineItems(true), Map.of());
        return onboard(mvc, mapper, body);
    }

    public static Onboarded onboard(MockMvc mvc, ObjectMapper mapper, Map<String, Object> body) throws Exception {
        MvcResult r = mvc.perform(post("/cases").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn();
        JsonNode n = mapper.readTree(r.getResponse().getContentAsString());
        return new Onboarded(n.get("caseId").asText(), n.get("guardianToken").asText(), n.get("recoveryCode").asText());
    }

    public static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String token) {
        return b.header(HEADER, token);
    }

    public static MockHttpServletRequestBuilder getMe(String token, String path) {
        return authed(get(path), token);
    }

    public static MockHttpServletRequestBuilder putJson(String token, String path, ObjectMapper mapper, Object body) throws Exception {
        return authed(put(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), token);
    }

    public static MockHttpServletRequestBuilder patchJson(String token, String path, ObjectMapper mapper, Object body) throws Exception {
        return authed(patch(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), token);
    }

    public static MockHttpServletRequestBuilder postJson(String path, ObjectMapper mapper, Object body) throws Exception {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    public static JsonNode json(ObjectMapper mapper, MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }
}
