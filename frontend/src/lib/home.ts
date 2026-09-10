import type { Me, Progress } from './types';

export type HomeState = 'ONBOARDING' | 'LOADING' | 'NOT_RECORDED' | 'SILENT' | 'CHANGES';

export function homeState(hasToken: boolean, me: Me | null, progress: Progress | null): HomeState {
  // 토큰 없음과 "토큰은 있는데 아직 안 왔음"은 다르다. 하나로 묶으면 다시 온 보호자에게
  // 시작 화면이 한 번 번쩍이고, 느린 연결에서는 그 사이 '시작하기'를 눌러 온보딩으로 들어간다.
  if (!hasToken) return 'ONBOARDING';
  if (!me) return 'LOADING';
  if (!me.recordedThisWeek) return 'NOT_RECORDED';
  // 경과를 못 받았으면 침묵으로 둔다. 없는 변화를 지어내는 것보다 안전하다.
  // changes와 transitions는 ProgressService가 항목·축마다 배타적으로 채운다 —
  // SUSTAINED가 FLUCTUATING으로 넘어가는 주는 changes가 아니라 transitions로만 간다.
  // 그래서 changes만 보고 침묵을 판단하면, 서버가 준 전환 문장이 있는 평범한 한 주를
  // "바뀐 것 없음"으로 잘못 말하게 된다 — v1이 reverted를 통째로 침묵 처리했던 것과
  // 같은 정보 손실이다(README §4). 침묵은 둘 다 비었을 때뿐이다.
  if (!progress || (progress.changes.length === 0 && progress.transitions.length === 0)) return 'SILENT';
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
