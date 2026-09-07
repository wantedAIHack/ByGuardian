import type { Me, Progress } from './types';

export type HomeState = 'ONBOARDING' | 'NOT_RECORDED' | 'SILENT' | 'CHANGES';

export function homeState(hasToken: boolean, me: Me | null, progress: Progress | null): HomeState {
  if (!hasToken || !me) return 'ONBOARDING';
  if (!me.recordedThisWeek) return 'NOT_RECORDED';
  // 경과를 못 받았으면 침묵으로 둔다. 없는 변화를 지어내는 것보다 안전하다.
  if (!progress || progress.changes.length === 0) return 'SILENT';
  return 'CHANGES';
}

export function densityPhrase(me: Me): string | null {
  if (me.totalWeeks < 2) return null;
  return `지금까지 ${me.totalWeeks}주 중 ${me.recordedWeeks}주 기록`;
}

/**
 * 준비 카드 문구. 개수는 반드시 서버가 준 배열의 길이에서 온다.
 * questions는 최대 3개이고 0개일 수 있어서, "3가지"를 상수로 박으면
 * 조용한 주에 앱이 없는 것을 있다고 말하게 된다.
 */
export function prepCardPhrase(count: number, emptyMessage: string | null): string | null {
  if (count > 0) return `여쭤볼 것 ${count}가지를 준비했습니다.`;
  return emptyMessage;
}
