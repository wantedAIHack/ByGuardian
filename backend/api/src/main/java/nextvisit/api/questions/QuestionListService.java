package nextvisit.api.questions;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.common.Json;
import nextvisit.api.common.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 2026-09-17 정리 설계 4.1~4.3: 보이는 목록, 보호자 확정, 다시 정리하기. */
@Service
@Transactional
public class QuestionListService {

    public static final int MAX_ITEMS = 8;
    public static final int MAX_LENGTH = 200;

    private final CaseRepository cases;
    private final QuestionCacheRepository caches;
    private final QuestionService questions;
    private final Json json;
    private final Clock clock;

    public QuestionListService(CaseRepository cases, QuestionCacheRepository caches, QuestionService questions,
                               Json json, Clock clock) {
        this.cases = cases;
        this.caches = caches;
        this.questions = questions;
        this.json = json;
        this.clock = clock;
    }

    public List<ConfirmedItem> visible(CaseEntity kase, QuestionCacheBody cache) {
        if (kase.getConfirmedQuestions() != null) {
            return List.of(json.fromJson(kase.getConfirmedQuestions(), ConfirmedItem[].class));
        }
        List<ConfirmedItem> out = new ArrayList<>();
        for (QuestionCacheBody.Q q : cache.questions()) {
            out.add(new ConfirmedItem(stableId("q" + q.rank(), q.sentence()), q.sentence(), q.originOrDefault(),
                false, q.type(), q.items(), q.signal(), q.basisOrEmpty()));
        }
        String[] extras = json.fromJson(kase.getExtraQuestions(), String[].class);
        for (int i = 0; i < extras.length; i++) {
            out.add(ConfirmedItem.caregiver(stableId("x" + (i + 1), extras[i]), extras[i]));
        }
        return out;
    }

    public boolean suggestionAvailable(CaseEntity kase, Optional<QuestionCache> cache) {
        return kase.getConfirmedAt() != null
            && cache.isPresent()
            && cache.get().getGeneratedAt().isAfter(kase.getConfirmedAt())
            && cache.get().getStatus() != QuestionCacheStatus.LLM_PENDING;
    }

    public static String generationStatus(Optional<QuestionCache> cache) {
        if (cache.isEmpty()) {
            return "TEMPLATE_ONLY";
        }
        return switch (cache.get().getStatus()) {
            case LLM_PENDING -> "PENDING";
            case LLM_DONE -> "DONE";
            case LLM_FAILED -> "FAILED";
            case READY -> "TEMPLATE_ONLY";
        };
    }

    public void save(AuthContext ctx, List<SaveQuestionsRequest.Item> given) {
        validate(given);
        CaseEntity kase = locked(ctx);
        QuestionCacheBody cache = questions.current(kase.getId()).orElseGet(() -> questions.refresh(kase.getId()));
        Map<String, ConfirmedItem> current = new HashMap<>();
        for (ConfirmedItem item : visible(kase, cache)) {
            current.put(item.id(), item);
        }
        boolean wasConfirmed = kase.getConfirmedQuestions() != null;
        List<ConfirmedItem> saved = new ArrayList<>();
        for (SaveQuestionsRequest.Item item : given) {
            String sentence = item.sentence().strip();
            ConfirmedItem base = item.id() == null ? null : current.get(item.id());
            if (base == null) {
                saved.add(ConfirmedItem.caregiver(newConfirmedId(), sentence));
            } else if (!wasConfirmed && base.isCaregiver()) {
                saved.add(ConfirmedItem.caregiver(newConfirmedId(), sentence));
            } else if (!wasConfirmed) {
                saved.add(new ConfirmedItem(newConfirmedId(), sentence, base.origin(),
                    !sentence.equals(base.sentence()), base.type(), base.items(), base.signal(), base.basis()));
            } else if (base.isCaregiver()) {
                saved.add(ConfirmedItem.caregiver(base.id(), sentence));
            } else {
                saved.add(new ConfirmedItem(base.id(), sentence, base.origin(),
                    base.edited() || !sentence.equals(base.sentence()),
                    base.type(), base.items(), base.signal(), base.basis()));
            }
        }
        kase.confirmQuestions(json.toJson(saved), Instant.now(clock));
        kase.setExtraQuestions("[]");
        cases.save(kase);
    }

    public void regenerate(AuthContext ctx) {
        CaseEntity kase = locked(ctx);
        if (kase.getConfirmedQuestions() == null) {
            questions.refresh(kase.getId());
            return;
        }
        Optional<QuestionCache> cache = caches.findByCaseId(kase.getId());
        boolean needRefresh = cache.isEmpty() || !cache.get().getGeneratedAt().isAfter(kase.getConfirmedAt());
        List<String> caregiver = confirmed(kase).stream().filter(ConfirmedItem::isCaregiver)
            .map(ConfirmedItem::sentence).toList();
        kase.clearConfirmedQuestions();
        kase.setExtraQuestions(json.toJson(caregiver));
        cases.save(kase);
        if (needRefresh) {
            questions.refresh(kase.getId());
        }
    }

    /** 옛 추가 질문 API 호환: 확정 목록이 있으면 그 안의 보호자 작성 질문만 바꾼다. */
    public void replaceCaregiver(CaseEntity kase, List<String> sentences) {
        List<ConfirmedItem> retained = confirmed(kase).stream().filter(i -> !i.isCaregiver()).toList();
        if (retained.size() + sentences.size() > MAX_ITEMS) {
            throw new ValidationException("질문은 " + MAX_ITEMS + "개까지입니다");
        }
        List<ConfirmedItem> replaced = new ArrayList<>(retained);
        for (String sentence : sentences) {
            replaced.add(ConfirmedItem.caregiver(newConfirmedId(), sentence));
        }
        kase.confirmQuestions(json.toJson(replaced), kase.getConfirmedAt());
    }

    private CaseEntity locked(AuthContext ctx) {
        return cases.findByIdForQuestionRefresh(ctx.kase().getId()).orElseThrow();
    }

    private List<ConfirmedItem> confirmed(CaseEntity kase) {
        return List.of(json.fromJson(kase.getConfirmedQuestions(), ConfirmedItem[].class));
    }

    private static void validate(List<SaveQuestionsRequest.Item> given) {
        if (given == null) {
            throw new ValidationException("질문 목록이 필요합니다");
        }
        if (given.size() > MAX_ITEMS) {
            throw new ValidationException("질문은 " + MAX_ITEMS + "개까지입니다");
        }
        Set<String> ids = new HashSet<>();
        for (SaveQuestionsRequest.Item item : given) {
            if (item == null || item.sentence() == null || item.sentence().isBlank()) {
                throw new ValidationException("빈 질문은 넣을 수 없습니다");
            }
            if (item.sentence().strip().length() > MAX_LENGTH) {
                throw new ValidationException("질문은 " + MAX_LENGTH + "자까지입니다");
            }
            if (item.id() != null && !ids.add(item.id())) {
                throw new ValidationException("같은 질문을 두 번 넣을 수 없습니다");
            }
        }
    }

    private static String newConfirmedId() {
        return "c-" + UUID.randomUUID();
    }

    static String stableId(String prefix, String sentence) {
        String normalized = Normalizer.normalize(sentence == null ? "" : sentence, Normalizer.Form.NFC);
        return prefix + "-" + Integer.toHexString(normalized.hashCode());
    }
}
