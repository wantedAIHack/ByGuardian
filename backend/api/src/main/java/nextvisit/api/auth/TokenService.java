package nextvisit.api.auth;

import java.security.SecureRandom;
import java.util.UUID;
import nextvisit.api.common.Hashing;
import org.springframework.stereotype.Component;

/** 설계 4절. 토큰은 UUID, 복구 코드는 헷갈리는 글자를 뺀 8자. DB에는 해시만. */
@Component
public class TokenService {

    public static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int RECOVERY_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String newToken() {
        return UUID.randomUUID().toString();
    }

    public String newRecoveryCode() {
        StringBuilder sb = new StringBuilder(RECOVERY_LENGTH);
        for (int i = 0; i < RECOVERY_LENGTH; i++) {
            sb.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    public String hash(String secret) {
        return Hashing.sha256Hex(secret);
    }
}
