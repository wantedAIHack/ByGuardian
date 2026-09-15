import { useId, type ReactNode } from 'react';

export function ScrollRegion({ label, children }: { label: string; children: ReactNode }) {
  const hintId = useId();
  return <div className="min-w-0">
    <p id={hintId} className="mb-3 text-small text-ink-soft no-print">좌우로 밀어 주차별 기록을 볼 수 있어요</p>
    <div role="region" aria-label={label} aria-describedby={hintId} tabIndex={0}
      className="scroll-hint overflow-x-auto min-w-0 max-w-full">{children}</div>
  </div>;
}
