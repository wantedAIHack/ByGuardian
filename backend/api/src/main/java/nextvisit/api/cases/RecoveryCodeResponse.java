package nextvisit.api.cases;

/** POST /me/recovery-code. 평문 코드를 돌려주는 유일한 자리이고, 이 응답 이후로는 해시만 남는다. */
public record RecoveryCodeResponse(String recoveryCode) {}
