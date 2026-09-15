import { ScrollRegion } from '../ui/ScrollRegion';
import { ObservationValue } from '../ui/ObservationValue';
import { PageHeader } from '../ui/PageHeader';
import { AsyncState } from '../ui/AsyncState';
import { useTrajectory } from '../lib/queries';
import { Collapse } from '../ui/Collapse';
import { Notice } from '../ui/Notice';
import type { AxisSeries } from '../lib/types';

function Series({ label, axes }: { label: string; axes: AxisSeries[] }) {
  return (
    <div className="flex flex-col gap-5">
      {axes.map((a) => (
        <div key={a.axis}>
          <p className="text-small text-ink-soft">{a.axisLabel}</p>
          <ScrollRegion label={`${label} ${a.axisLabel} 주차별 기록`}>
            <div className="flex min-w-max gap-5 pt-2">
              {a.values.map((p) => (
                <div key={p.week} className="min-w-[92px]">
                  <p className="text-small text-ink-faint">{p.week}주</p>
                  <ObservationValue point={p} />
                </div>
              ))}
            </div>
          </ScrollRegion>
        </div>
      ))}
    </div>
  );
}

export function Trajectory() {
  const query = useTrajectory();
  const { data, isPending } = query;

  return (
    <main className="app-page">
      <PageHeader title="전체 기록" backTo="/" focusKey="trajectory" />
      <Notice>지난 값 유지: 달라진 것 없음으로 이어간 기록</Notice>

      {isPending ? <AsyncState kind="loading" message="불러오는 중입니다…" /> : null}
      {query.isError ? <AsyncState kind="error" message="전체 기록을 불러오지 못했습니다." onRetry={() => { void query.refetch(); }} /> : null}

      <div className="flex flex-col gap-10 pt-10">
        {(data ?? []).map((t) =>
          t.changed ? (
            <section key={t.code} className="note-surface min-w-0">
              <h2 className="pb-3 font-semibold">{t.label}</h2>
              <Series label={t.label} axes={t.axes} />
            </section>
          ) : (
            <section key={t.code} className="note-surface min-w-0">
              <Collapse label={`${t.label} — 바뀐 것 없음`}>
                <Series label={t.label} axes={t.axes} />
              </Collapse>
            </section>
          ),
        )}
      </div>
    </main>
  );
}
