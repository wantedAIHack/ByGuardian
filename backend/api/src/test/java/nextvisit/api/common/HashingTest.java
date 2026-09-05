package nextvisit.api.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class HashingTest {
    @Test
    void sha256HexIsStableAnd64Chars() {
        String h = Hashing.sha256Hex("abc");
        assertEquals(64, h.length());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h);
        assertNotEquals(h, Hashing.sha256Hex("abd"));
    }
}
