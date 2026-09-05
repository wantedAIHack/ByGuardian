package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ObservationTest {

    @Test
    void holdsWeekValueAndSource() {
        Observation o = new Observation(3, 2, Source.CONFIRMED);
        assertEquals(3, o.week());
        assertEquals(2, o.value());
        assertEquals(Source.CONFIRMED, o.source());
    }

    @Test
    void rejectsWeekBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new Observation(0, 2, Source.CARRIED));
    }
}
