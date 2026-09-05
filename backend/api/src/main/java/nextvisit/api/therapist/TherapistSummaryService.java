package nextvisit.api.therapist;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import nextvisit.api.auth.Guardian;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.auth.TokenService;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.common.Json;
import nextvisit.api.common.NotFoundException;
import nextvisit.api.common.WeekCalculator;
import nextvisit.api.progress.TrajectoryMapper;
import nextvisit.api.questions.PrepCardService;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionService;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotRepository;
import nextvisit.engine.SignalAction;
import nextvisit.engine.SignalKey;
import nextvisit.engine.SignalKind;
import nextvisit.engine.TimeTag;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TherapistSummaryService {

    public static final String DISCLAIMER =
        "이 기록은 보호자가 가정에서 관찰해 남긴 것입니다. 측정이나 평가가 아니며 검사 결과를 포함하지 않습니다.";

    public record Link(String url, String token) {}

    private final TherapistLinkRepository links;
    private final CaseRepository cases;
    private final GuardianRepository guardians;
    private final SnapshotRepository snapshots;
    private final QuestionService questions;
    private final TrajectoryMapper trajectories;
    private final TokenService tokens;
    private final WeekCalculator weeks;
    private final Json json;
    private final Clock clock;

    public TherapistSummaryService(TherapistLinkRepository links, CaseRepository cases, GuardianRepository guardians,
                                   SnapshotRepository snapshots, QuestionService questions, TrajectoryMapper trajectories,
                                   TokenService tokens, WeekCalculator weeks, Json json, Clock clock) {
        this.links = links;
        this.cases = cases;
        this.guardians = guardians;
        this.snapshots = snapshots;
        this.questions = questions;
        this.trajectories = trajectories;
        this.tokens = tokens;
        this.weeks = weeks;
        this.json = json;
        this.clock = clock;
    }

    /** 새 링크를 발급하고 이전 활성 링크를 폐기한다. */
    public Link issueLink(CaseEntity kase) {
        links.findFirstByCaseIdAndRevokedFalse(kase.getId()).ifPresent(old -> {
            old.revoke();
            links.save(old);
        });
        String token = tokens.newToken();
        links.save(new TherapistLink(kase.getId(), tokens.hash(token), Instant.now(clock)));
        return new Link("/t/" + token, token);
    }

    @Transactional(readOnly = true)
    public TherapistSummaryDto summary(String token) {
        TherapistLink link = links.findByTokenHash(tokens.hash(token))
            .filter(l -> !l.isRevoked())
            .orElseThrow(() -> new NotFoundException("링크가 없거나 폐기됐습니다"));
        CaseEntity kase = cases.findById(link.getCaseId()).orElseThrow(() -> new NotFoundException("케이스가 없습니다"));
        List<Snapshot> snaps = snapshots.findByCaseIdOrderByWeekAsc(kase.getId());

        List<Integer> weekNumbers = snaps.stream().map(Snapshot::getWeek).toList();

        TreeMap<SignalKey, List<Integer>> signalWeeks = new TreeMap<>();
        List<TherapistSummaryDto.FreeNote> notes = new ArrayList<>();
        int confirmed = 0;
        for (Snapshot s : snaps) {
            SnapshotBody body = json.fromJson(s.getBody(), SnapshotBody.class);
            if (body.painSignal() != null) {
                for (var e : body.painSignal().entrySet()) {
                    for (String kind : e.getValue()) {
                        signalWeeks.computeIfAbsent(new SignalKey(SignalAction.valueOf(e.getKey()), SignalKind.valueOf(kind)), k -> new ArrayList<>())
                            .add(s.getWeek());
                    }
                }
            }
            if (body.freeNote() != null) {
                String tag = body.freeNote().timeTag();
                notes.add(new TherapistSummaryDto.FreeNote(s.getWeek(), body.freeNote().text(), tag,
                    tag == null ? null : TimeTag.valueOf(tag).phrase()));
            }
            if (!s.isNoChange()) {
                confirmed++;
            }
        }
        List<TherapistSummaryDto.Signal> signals = new ArrayList<>();
        signalWeeks.forEach((k, ws) -> signals.add(new TherapistSummaryDto.Signal(
            k.action().name(), k.action().phrase(), k.kind().name(), PrepCardService.kindLabel(k.kind()), ws)));

        Map<UUID, String> relationById = new HashMap<>();
        for (Guardian g : guardians.findByCaseId(kase.getId())) {
            relationById.put(g.getId(), g.getRelation());
        }
        List<String> authors = new ArrayList<>();
        List<TherapistSummaryDto.AuthorChange> changes = new ArrayList<>();
        UUID prevAuthor = null;
        for (Snapshot s : snaps) {
            String rel = relationById.getOrDefault(s.getAuthorId(), "?");
            if (!authors.contains(rel)) {
                authors.add(rel);
            }
            if (prevAuthor != null && !prevAuthor.equals(s.getAuthorId())) {
                changes.add(new TherapistSummaryDto.AuthorChange(s.getWeek(), relationById.getOrDefault(prevAuthor, "?"), rel));
            }
            prevAuthor = s.getAuthorId();
        }

        int currentWeek = weeks.currentWeek(kase.getStartDate());
        int lastRecorded = snaps.isEmpty() ? 0 : snaps.get(snaps.size() - 1).getWeek();
        boolean recordedThisWeek = lastRecorded == currentWeek;
        int totalWeeks = Math.max(1, Math.max(lastRecorded, recordedThisWeek ? currentWeek : currentWeek - 1));

        List<String> qs = questions.current(kase.getId())
            .map(QuestionCacheBody::questions).orElse(List.of()).stream().map(QuestionCacheBody.Q::sentence).toList();
        List<String> extra = Arrays.asList(json.fromJson(kase.getExtraQuestions(), String[].class));

        String generatedAt = ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new TherapistSummaryDto(generatedAt, weekNumbers, trajectories.items(snaps), signals, kase.signalsEnabled(),
            notes, qs, extra, new TherapistSummaryDto.Density(totalWeeks, snaps.size(), confirmed, authors), changes, DISCLAIMER);
    }
}
