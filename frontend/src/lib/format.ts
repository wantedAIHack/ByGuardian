/** ISO 날짜(YYYY-MM-DD)만 다룬다. 시간대 변환을 타지 않도록 문자열을 직접 자른다. */
function parts(iso: string): { y: number; m: number; d: number } {
  const [y, m, d] = iso.split('-').map(Number);
  return { y: y ?? 0, m: m ?? 0, d: d ?? 0 };
}

export function formatDate(iso: string): string {
  const { m, d } = parts(iso);
  return `${m}월 ${d}일`;
}

function daysBetween(fromIso: string, toIso: string): number {
  const a = parts(fromIso);
  const b = parts(toIso);
  const from = Date.UTC(a.y, a.m - 1, a.d);
  const to = Date.UTC(b.y, b.m - 1, b.d);
  return Math.round((to - from) / 86_400_000);
}

/** 오늘부터 사흘 안쪽만 말로 적는다. 그 밖은 null이라 호출자가 날짜만 쓴다. */
export function relativeDay(iso: string, todayIso: string): string | null {
  const diff = daysBetween(todayIso, iso);
  if (diff === 0) return '오늘';
  if (diff === 1) return '내일';
  if (diff === 2) return '모레';
  if (diff === 3) return '3일 뒤';
  return null;
}

export function dDayPhrase(iso: string, todayIso: string): string {
  const rel = relativeDay(iso, todayIso);
  const base = `${formatDate(iso)} 진료가 있습니다`;
  return rel ? `${base} (${rel})` : base;
}

/** 외래가 사흘 안쪽인가. 홈의 상태 E 판정. */
export function isVisitSoon(iso: string | null, todayIso: string): boolean {
  if (!iso) return false;
  const diff = daysBetween(todayIso, iso);
  return diff >= 0 && diff <= 3;
}
