export const ONBOARDING_DRAFT = 'draft:onboarding';
export const DEMO_ONBOARDING_DRAFT = 'draft:onboarding:demo';
/** 마이크 안내를 첫 사용 때 한 번만 보여주기 위한 플래그 키. 값은 boolean, 케이스에 묶이지 않는다. */
export const DICTATION_HINT_SEEN = 'seen:dictation-hint';

export function weeklyDraftKey(caseId: string, week: number): string {
  return `draft:${caseId}:${week}`;
}

export function loadDraft<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    return JSON.parse(raw) as T;
  } catch {
    // 형식이 바뀌었거나 저장이 잘린 경우. 붙들고 있어봐야 화면만 깨진다.
    try {
      localStorage.removeItem(key);
    } catch {
      /* 지우는 것도 막혔다면 그냥 없는 셈 친다 */
    }
    return null;
  }
}

export function saveDraft<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* 용량 초과나 비공개 모드. 초안을 못 남길 뿐 흐름은 이어진다. */
  }
}

export function clearDraft(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* 위와 같다 */
  }
}
