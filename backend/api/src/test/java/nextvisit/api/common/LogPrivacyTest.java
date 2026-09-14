package nextvisit.api.common;

import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.putJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import nextvisit.api.ApiTestSupport.Onboarded;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import nextvisit.api.questions.QuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@SpringBootTest(properties = "logging.level.nextvisit.api.common.GlobalExceptionHandler=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LogPrivacyTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @Autowired GlobalExceptionHandler errors;
    @MockitoSpyBean QuestionService questions;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfig.DEFAULT_TODAY);
    }

    @Test
    void refreshFailureLogsOnlyAnOpaqueCode(CapturedOutput output) throws Exception {
        Onboarded onboarded = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(onboarded.caseId());
        String freeNote = "FREE_NOTE_SENTINEL";
        String credential = "CREDENTIAL_SENTINEL";
        String template = "TEMPLATE_SENTINEL";
        String generated = "GENERATED_SENTINEL";
        String unexpected = "UNEXPECTED_EXCEPTION_SENTINEL";
        doThrow(new RuntimeException(String.join(" ", caseId.toString(), freeNote,
            credential, template, generated, unexpected))).when(questions).refresh(caseId);

        clock.advanceDays(7);
        mvc.perform(putJson(onboarded.token(), "/me/weeks/2", mapper, weeklyWith(freeNote)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.questionsRefreshed").value(false));

        assertThat(output).contains("QUESTION_REFRESH_FAILED");
        assertThat(output).doesNotContain(caseId.toString(), freeNote, credential, template,
            generated, unexpected);
    }

    @Test
    void commonErrorLogsKeepFixedCodesAndNeverRenderThrowableContent(CapturedOutput output) {
        String caseId = UUID.randomUUID().toString();
        String freeNote = "FREE_NOTE_SENTINEL";
        String credential = "CREDENTIAL_SENTINEL";
        String template = "TEMPLATE_SENTINEL";
        String generated = "GENERATED_SENTINEL";
        String unexpected = "UNEXPECTED_EXCEPTION_SENTINEL";
        String sensitive = String.join(" ", caseId, freeNote, credential, template,
            generated, unexpected);

        assertThat(errors.conflict(new DataIntegrityViolationException(sensitive)))
            .satisfies(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(response.getBody()).isEqualTo(new ApiError("CONFLICT",
                    "이미 저장된 기록입니다. 새로고침 후 다시 시도해 주세요"));
            });
        assertThat(errors.noHandler(new NoResourceFoundException(HttpMethod.GET, "/" + sensitive)))
            .satisfies(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(response.getBody()).isEqualTo(new ApiError("NOT_FOUND",
                    "요청한 경로를 찾을 수 없습니다"));
            });
        assertThat(errors.methodNotAllowed(new HttpRequestMethodNotSupportedException(sensitive)))
            .satisfies(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
                assertThat(response.getBody()).isEqualTo(new ApiError("METHOD_NOT_ALLOWED",
                    "이 경로에서 지원하지 않는 방식입니다"));
            });
        assertThat(errors.unexpected(new RuntimeException(sensitive)))
            .satisfies(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(response.getBody()).isEqualTo(new ApiError("INTERNAL",
                    "요청을 처리하지 못했습니다"));
            });

        assertThat(output).contains("DATA_INTEGRITY_CONFLICT", "ROUTE_NOT_FOUND",
            "HTTP_METHOD_NOT_ALLOWED", "UNEXPECTED_FAILURE");
        assertThat(output).doesNotContain(caseId, freeNote, credential, template, generated,
            unexpected);
    }

    private static Map<String, Object> weeklyWith(String freeNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("noChange", true);
        body.put("changedItems", Map.of());
        body.put("painSignal", Map.of());
        body.put("sleep", 1);
        body.put("freeNote", Map.of("text", freeNote, "timeTag", "ANY"));
        return body;
    }
}
