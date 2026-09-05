package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationSetTest {

    @Test
    void strokeSetHasEightItemsInSpecOrder() {
        assertEquals(
            List.of("transfer", "ambulation", "stairs", "toilet", "dressing", "grooming", "bathing", "feeding"),
            ObservationSet.STROKE.codes());
    }

    @Test
    void axesFollowSpecTable() {
        assertEquals(java.util.Set.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY),
            ObservationSet.STROKE.item("ambulation").axes());
        assertEquals(java.util.Set.of(Axis.LEVEL, Axis.CONSISTENCY),
            ObservationSet.STROKE.item("toilet").axes());
        assertEquals(java.util.Set.of(Axis.LEVEL, Axis.CONSISTENCY, Axis.HAND),
            ObservationSet.STROKE.item("feeding").axes());
    }

    @Test
    void orderIsTablePosition() {
        assertEquals(0, ObservationSet.STROKE.order("transfer"));
        assertEquals(1, ObservationSet.STROKE.order("ambulation"));
        assertEquals(7, ObservationSet.STROKE.order("feeding"));
    }

    @Test
    void unknownCodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> ObservationSet.STROKE.item("nope"));
    }

    @Test
    void labelsFollowSpec() {
        assertEquals("혼자 하심", Labels.of(Axis.LEVEL, 3));
        assertEquals("대부분 도움", Labels.of(Axis.LEVEL, 0));
        assertEquals("워커", Labels.of(Axis.AID, 1));
        assertEquals("지팡이", Labels.of(Axis.AID, 2));
        assertEquals("좋은 날만", Labels.of(Axis.CONSISTENCY, 0));
        assertEquals("안 씀", Labels.of(Axis.HAND, 0));
        assertThrows(IllegalArgumentException.class, () -> Labels.of(Axis.LEVEL, 4));
    }

    @Test
    void phrasesAreShort() {
        assertEquals("옮겨 앉기", ObservationSet.STROKE.item("transfer").phrase());
        assertEquals("집 안에서 걷기", ObservationSet.STROKE.item("ambulation").phrase());
        assertTrue(ObservationSet.STROKE.item("transfer").label().length() > "옮겨 앉기".length());
    }
}
