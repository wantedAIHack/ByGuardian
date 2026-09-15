import { Link } from 'react-router';
import { useTrajectory } from '../lib/queries';
import { Collapse } from '../ui/Collapse';
import { Notice } from '../ui/Notice';
import type { AxisSeries } from '../lib/types';

function Series({ axes }: { axes: AxisSeries[] }) {
  return (
    <div className="flex flex-col gap-5">
      {axes.map((a) => (
        <div key={a.axis}>
          <p className="text-small text-ink-soft">{a.axisLabel}</p>
          <div className="scroll-hint overflow-x-auto">
            <div className="flex min-w-max gap-5 pt-2">
              {a.values.map((p) => (
                <div key={p.week} className="min-w-[92px]">
                  <p className="text-small text-ink-faint">{p.week}주</p>
                  {/* CARRIED는 '달라진 것 없음'으로 이어진 주다. 옅게 두되 숨기지 않는다. */}
                  <p
                    data-carried={p.source === 'CARRIED' ? 'true' : 'false'}
                    className={p.source === 'CARRIED' ? 'text-ink-faint' : ''}
                  >
                    {p.label}
                  </p>
                </div>
              ))}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

export function Trajectory() {
  const { data, isPending } = useTrajectory();

  return (
    <main className="mx-auto max-w-lg px-gutter py-10">
      <Link
        className="inline-flex min-h-[48px] items-center text-small text-ink-soft underline"
        to="/"
      >
        ← 홈
      </Link>
      <h1 className="pt-6 text-title font-semibold">전체 기록</h1>
      <Notice>옅은 값은 &lsquo;달라진 것 없음&rsquo;으로 이어진 주입니다</Notice>

      {isPending ? <p className="pt-8">불러오는 중입니다…</p> : null}

      <div className="flex flex-col gap-10 pt-10">
        {(data ?? []).map((t) =>
          t.changed ? (
            <section key={t.code}>
              <h2 className="pb-3 font-semibold">{t.label}</h2>
              <Series axes={t.axes} />
            </section>
          ) : (
            <section key={t.code}>
              <Collapse label={`${t.label} — 바뀐 것 없음`}>
                <Series axes={t.axes} />
              </Collapse>
            </section>
          ),
        )}
      </div>
    </main>
  );
}
