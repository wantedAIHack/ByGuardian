import type { Evidence } from '../../lib/types';
import { ObservationValue } from '../../ui/ObservationValue';
import { ScrollRegion } from '../../ui/ScrollRegion';

export function EvidenceView({ evidence }: { evidence: Evidence }) {
  return (
    <div className="flex flex-col gap-4">
      {evidence.items.map((it) => (
        <div key={`${it.code}-${it.axis}`}>
          <p className="text-small text-ink-soft">{it.label} · {it.axisLabel}</p>
          <ScrollRegion label={`${it.label} ${it.axisLabel} 주차별 기록`}>
            <div className="flex min-w-max gap-5 pt-1">
              {it.values.map((point) => (
                <div key={point.week} className="min-w-[92px]">
                  <p className="text-small text-ink-faint">{point.week}주</p>
                  <ObservationValue point={point} />
                </div>
              ))}
            </div>
          </ScrollRegion>
        </div>
      ))}
      {evidence.signal ? (
        <p className="text-small text-ink-soft">
          {evidence.signal.actionLabel} · {evidence.signal.kindLabel} —{' '}
          {evidence.signal.weeks.join('주, ')}주
        </p>
      ) : null}
    </div>
  );
}
