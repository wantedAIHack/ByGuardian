import type { Point } from '../lib/types';

/**
 * 값 한 칸. 축 궤적의 Point와, v2 문항 항목에서 만든 한 주치 답이 같은 모양으로
 * 보이도록 label과 source만 읽는다.
 */
export function ObservationValue({ point }: { point?: Point | { label: string; source: string } }) {
  if (!point) return <span>미기록</span>;
  const sourceLabel = point.source === 'CARRIED' ? '지난 값 유지'
    : point.source === 'CONFIRMED' ? '직접 확인' : null;
  return <span className="observation-value block" data-carried={point.source === 'CARRIED'}>
    <span>{point.label}</span>
    {sourceLabel ? <span className="block text-small text-ink-soft">{sourceLabel}</span> : null}
  </span>;
}
