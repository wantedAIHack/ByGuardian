package nextvisit.api.questions;

import static nextvisit.api.ApiTestSupport.onboardDefault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import nextvisit.api.ApiTestSupport.Onboarded;
import nextvisit.api.auth.Guardian;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.demo.DemoSeedWriter;
import nextvisit.api.engine.EngineBridge;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotKind;
import nextvisit.api.snapshots.SnapshotRepository;
import nextvisit.engine.CaseInput;
import nextvisit.engine.Pipeline;
import nextvisit.engine.demo.DemoSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestionServiceTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CaseRepository cases;
    @Autowired GuardianRepository guardians;
    @Autowired SnapshotRepository snapshots;
    @Autowired QuestionCacheRepository caches;
    @Autowired QuestionService questions;
    @Autowired DemoSeedWriter writer;
    @Autowired EngineBridge bridge;

    @Test
    void baselineOnlyYieldsNoQuestions() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(o.caseId());
        QuestionCacheBody body = questions.refresh(caseId);
        assertTrue(body.questions().isEmpty());
        assertEquals(0, body.engineDetectionCount());
        assertEquals(1, caches.findByCaseId(caseId).orElseThrow().getWeek());
        assertEquals("READY", caches.findByCaseId(caseId).orElseThrow().getStatus());
        assertTrue(questions.current(caseId).orElseThrow().questions().isEmpty());
    }

    @Test
    void demoSeedYieldsTheThreeReadmeQuestionsAsTemplates() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(o.caseId());
        CaseEntity kase = cases.findById(caseId).orElseThrow();
        Guardian author = guardians.findByCaseId(caseId).get(0);

        List<Snapshot> written = writer.write(kase, author);
        assertEquals(6, written.size());
        assertEquals(6, snapshots.findByCaseIdOrderByWeekAsc(caseId).size());
        Snapshot w2 = snapshots.findByCaseIdAndWeek(caseId, 2).orElseThrow();
        assertTrue(w2.isNoChange());
        assertEquals(SnapshotKind.FULL_RECHECK, snapshots.findByCaseIdAndWeek(caseId, 4).orElseThrow().getKind());
        SnapshotBody b3 = mapper.readValue(snapshots.findByCaseIdAndWeek(caseId, 3).orElseThrow().getBody(), SnapshotBody.class);
        assertEquals("오후만 되면 오른쪽 어깨를 자꾸 만지신다", b3.freeNote().text());
        assertEquals("AFTERNOON", b3.freeNote().timeTag());
        assertEquals("CARRIED", mapper.readValue(w2.getBody(), SnapshotBody.class).items().get("toilet").level().source());

        QuestionCacheBody body = questions.refresh(caseId);
        assertEquals(3, body.questions().size());
        assertEquals("화장실 이용은 혼자 하심으로 바뀌셨는데 집 안에서 걷기는 6주째 그대로입니다. 집 안에서 걷기는 왜 안 늘고 있을까요?", body.questions().get(0).sentence());
        assertEquals("일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", body.questions().get(1).sentence());
        assertEquals("식사는 혼자 하심으로 바뀌셨는데 마비된 손은 안 씀으로 바뀌었습니다. 괜찮은 걸까요?", body.questions().get(2).sentence());
        assertEquals("TEMPLATE", body.questions().get(0).source());
        assertEquals(body.questions().get(0).templateSentence(), body.questions().get(0).sentence());
        assertEquals("STALL_WITH_PAIN", body.questions().get(1).type());
        assertEquals("STANDING", body.questions().get(1).signal().action());
        assertEquals("GRIMACE", body.questions().get(1).signal().kind());
        assertEquals(List.of("toilet", "ambulation"), body.questions().get(0).items());
        assertEquals(20, body.engineDetectionCount());
        assertEquals(6, caches.findByCaseId(caseId).orElseThrow().getWeek());
    }

    @Test
    void bridgeReproducesEngineSeedExactly() throws Exception {
        Onboarded o = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(o.caseId());
        CaseEntity kase = cases.findById(caseId).orElseThrow();
        writer.write(kase, guardians.findByCaseId(caseId).get(0));

        CaseInput fromDb = bridge.toCaseInput(kase, snapshots.findByCaseIdOrderByWeekAsc(caseId));
        assertEquals(Pipeline.run(DemoSeed.stroke()).sentences(), Pipeline.run(fromDb).sentences());
        assertEquals(6, fromDb.signals().size());
        assertEquals(6, fromDb.notes().size());
    }

    @Test
    void signalsAreNotAskedWhenCaseHasThemDisabled() throws Exception {
        Onboarded o = nextvisit.api.ApiTestSupport.onboard(mvc, mapper,
            nextvisit.api.ApiTestSupport.onboardingBody("딸", "RIGHT", "NONE", nextvisit.api.ApiTestSupport.baselineItems(true), null));
        UUID caseId = UUID.fromString(o.caseId());
        CaseEntity kase = cases.findById(caseId).orElseThrow();
        writer.write(kase, guardians.findByCaseId(caseId).get(0));
        CaseInput fromDb = bridge.toCaseInput(kase, snapshots.findByCaseIdOrderByWeekAsc(caseId));
        assertTrue(fromDb.signals().isEmpty());
        assertTrue(Pipeline.run(fromDb).patterns().isEmpty());
    }
}
