import type { Point } from '../lib/types';

export function ObservationValue({ point }: { point?: Point }) {
  if (!point) return <span>미기록</span>;
  const sourceLabel = point.source === 'CARRIED' ? '지난 값 유지'
    : point.source === 'CONFIRMED' ? '직접 확인' : null;
  return <span className="observation-value block" data-carried={point.source === 'CARRIED'}>
    <span>{point.label}</span>
    {sourceLabel ? <span className="block text-small text-ink-soft">{sourceLabel}</span> : null}
  </span>;
}
