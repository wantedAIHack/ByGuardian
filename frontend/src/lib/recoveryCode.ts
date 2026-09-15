const KEY = 'nextvisit.recovery-code';

/**
 * 서버는 이어받기 코드의 해시만 들고 있다. 평문은 발급되는 두 순간
 * (온보딩 응답, 재발급 응답)에만 존재하고 그 뒤로는 어디에도 없다.
 * 그래서 "현재 코드 보여주기"는 서버가 답해 줄 수 있는 질문이 아니다.
 *
 * 발급될 때 이 기기에 적어 둔다. 보호자 토큰이 이미 같은 localStorage에
 * 있으므로 위협 모델은 실질적으로 달라지지 않는다 — 이 저장소를 읽을 수
 * 있는 쪽은 이미 그 케이스에 접근할 수 있다.
 *
 * 다만 이 코드가 없는 기기가 정상적으로 존재한다. 다른 기기에서 이어받은
 * 보호자, 저장소를 지운 사람. 화면은 없을 때를 정직하게 말해야 한다.
 */
export function saveRecoveryCode(code: string): void {
  try {
    localStorage.setItem(KEY, code);
  } catch {
    /* 사파리 비공개 모드에서 던진다. 못 적어두면 설정에서 안 보일 뿐이다. */
  }
}

export function loadRecoveryCode(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function clearRecoveryCode(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* 위와 같다 */
  }
}
