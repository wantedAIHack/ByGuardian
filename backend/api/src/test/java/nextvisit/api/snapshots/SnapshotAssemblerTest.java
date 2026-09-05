package nextvisit.api.snapshots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.Diagnosis;
import nextvisit.api.cases.PareticSide;
import nextvisit.api.cases.VerbalDifficulty;
import nextvisit.api.common.ValidationException;
import org.junit.jupiter.api.Test;

/** 조립기의 검증 규칙을 스프링 없이 직접 검사한다. 설계 3절. */
class SnapshotAssemblerTest {

    private final SnapshotAssembler assembler = new SnapshotAssembler();

    private static CaseEntity kase(PareticSide side, VerbalDifficulty verbal) {
        return new CaseEntity("stroke", LocalDate.of(2026, 9, 5), Diagnosis.STROKE, side, verbal, null, "hash", Instant.EPOCH);
    }

    private static final CaseEntity ENABLED = kase(PareticSide.RIGHT, VerbalDifficulty.OFTEN);
    private static final CaseEntity DISABLED = kase(PareticSide.UNKNOWN, VerbalDifficulty.NONE);

    private static ItemInput in(Integer level, Integer aid, Integer consistency, Integer hand, String note) {
        return new ItemInput(level, aid, consistency, hand, note);
    }

    /** 8항목 전부. mobility는 aid 1, 모두 consistency 2, hand 항목은 handEnabled일 때만 1. */
    private static Map<String, ItemInput> allEight(boolean handEnabled) {
        Map<String, ItemInput> m = new LinkedHashMap<>();
        for (String code : List.of("transfer", "ambulation", "stairs", "toilet", "dressing", "grooming", "bathing", "feeding")) {
            boolean mobility = code.equals("transfer") || code.equals("ambulation") || code.equals("stairs");
            boolean handItem = code.equals("dressing") || code.equals("grooming") || code.equals("feeding");
            m.put(code, in(2, mobility ? 1 : null, 2, handItem && handEnabled ? 1 : null, null));
        }
        return m;
    }

    private static String message(Runnable r) {
        return assertThrows(ValidationException.class, r::run).getMessage();
    }

    @Test
    void rejectsAxisNotDeclaredForItem() {
        Map<String, ItemInput> items = allEight(true);
        items.put("toilet", in(2, 1, 2, null, null));   // toilet has no AID
        String msg = message(() -> assembler.build(ENABLED, items, null, Map.of(), null, null));
        assertTrue(msg.contains("toilet") && msg.contains("AID"), msg);
    }

    @Test
    void rejectsMissingRequiredAxis() {
        Map<String, ItemInput> items = allEight(true);
        items.put("toilet", in(2, null, null, null, null)); // consistency required
        String msg = message(() -> assembler.build(ENABLED, items, null, Map.of(), null, null));
        assertTrue(msg.contains("toilet") && msg.contains("CONSISTENCY"), msg);
    }

    @Test
    void rejectsOutOfRangeValue() {
        Map<String, ItemInput> items = allEight(true);
        items.put("toilet", in(4, null, 2, null, null));
        String msg = message(() -> assembler.build(ENABLED, items, null, Map.of(), null, null));
        assertTrue(msg.contains("toilet") && msg.contains("LEVEL") && msg.contains("0..3"), msg);
    }

    @Test
    void handAbsentIsAcceptedWhenDisabledAndPresentIsRejected() {
        SnapshotBody body = assembler.build(DISABLED, allEight(false), null, null, null, null);
        assertNull(body.items().get("feeding").hand());
        Map<String, ItemInput> withHand = allEight(true);
        String msg = message(() -> assembler.build(DISABLED, withHand, null, null, null, null));
        assertTrue(msg.contains("HAND"), msg);
    }

    @Test
    void carriedCopiesValuesKeepsAxisShapeAndDropsNote() {
        Map<String, ItemInput> baseline = allEight(true);
        baseline.put("toilet", in(3, null, 2, null, "혼자 가심"));
        SnapshotBody previous = assembler.build(ENABLED, baseline, null, Map.of(), null, null);
        assertEquals("혼자 가심", previous.items().get("toilet").note());

        SnapshotBody weekly = assembler.build(ENABLED, Map.of(), previous, Map.of(), null, null);
        SnapshotBody.ItemValues toilet = weekly.items().get("toilet");
        assertEquals(3, toilet.level().value());
        assertEquals("CARRIED", toilet.level().source());
        assertEquals("CARRIED", toilet.consistency().source());
        assertNull(toilet.aid());
        assertNull(toilet.note());
        assertEquals("CARRIED", weekly.items().get("ambulation").aid().source());
        assertEquals(1, weekly.items().get("feeding").hand().value());
        assertEquals(8, weekly.items().size());
    }

    @Test
    void disabledCaseStoresNullPainSignalEvenWhenSent() {
        SnapshotBody body = assembler.build(DISABLED, allEight(false), null, Map.of("STANDING", List.of("GRIMACE")), null, null);
        assertNull(body.painSignal());
    }

    @Test
    void emptyKindListOmitsThatActionOnly() {
        Map<String, List<String>> signals = new LinkedHashMap<>();
        signals.put("STANDING", List.of());
        signals.put("EATING", List.of("VOCAL"));
        SnapshotBody body = assembler.build(ENABLED, allEight(true), null, signals, null, null);
        assertEquals(Map.of("EATING", List.of("VOCAL")), body.painSignal());
    }

    @Test
    void rejectsUnknownActionOrKind() {
        assertTrue(message(() -> assembler.build(ENABLED, allEight(true), null, Map.of("FLYING", List.of("GRIMACE")), null, null)).contains("FLYING"));
        assertTrue(message(() -> assembler.build(ENABLED, allEight(true), null, Map.of("STANDING", List.of("SMILE")), null, null)).contains("SMILE"));
    }

    @Test
    void sleepMustBeWithinRange() {
        assertTrue(message(() -> assembler.build(ENABLED, allEight(true), null, Map.of(), 3, null)).contains("sleep"));
        assertEquals(2, assembler.build(ENABLED, allEight(true), null, Map.of(), 2, null).sleep());
    }

    @Test
    void freeNoteIsTrimmedValidatedAndBlankBecomesNull() {
        assertNull(assembler.build(ENABLED, allEight(true), null, Map.of(), null, new FreeNoteInput("   ", "AFTERNOON")).freeNote());
        SnapshotBody ok = assembler.build(ENABLED, allEight(true), null, Map.of(), null, new FreeNoteInput("  오후에 어깨를 만지심  ", "AFTERNOON"));
        assertEquals("오후에 어깨를 만지심", ok.freeNote().text());
        assertEquals("AFTERNOON", ok.freeNote().timeTag());
        assertTrue(message(() -> assembler.build(ENABLED, allEight(true), null, Map.of(), null, new FreeNoteInput("x".repeat(501), null))).contains("500"));
        assertTrue(message(() -> assembler.build(ENABLED, allEight(true), null, Map.of(), null, new FreeNoteInput("x", "NIGHT"))).contains("NIGHT"));
    }
}
