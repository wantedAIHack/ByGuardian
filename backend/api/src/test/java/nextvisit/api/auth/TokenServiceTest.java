package nextvisit.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    private final TokenService tokens = new TokenService();

    @Test
    void tokenIsUuid() {
        UUID.fromString(tokens.newToken());
        assertNotEquals(tokens.newToken(), tokens.newToken());
    }

    @Test
    void recoveryCodeIsEightCharsFromSafeAlphabet() {
        for (int i = 0; i < 200; i++) {
            String code = tokens.newRecoveryCode();
            assertEquals(8, code.length());
            for (char c : code.toCharArray()) {
                assertTrue(TokenService.RECOVERY_ALPHABET.indexOf(c) >= 0, "bad char " + c);
            }
        }
    }

    @Test
    void hashIsSha256Hex() {
        assertEquals(64, tokens.hash("x").length());
        assertEquals(tokens.hash("x"), tokens.hash("x"));
    }
}
