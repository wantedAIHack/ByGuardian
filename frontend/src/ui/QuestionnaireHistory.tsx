import type { Trajectory } from '../lib/types';

/** Descriptive observations only: category choices are never plotted as numeric scores. */
export function QuestionnaireHistory({ item }: { item: Trajectory }) {
  const observations = item.observations ?? [];
  const weeks = [...new Set(observations.map((o) => o.week))].sort((a, b) => b - a);
  return (
    <div className="pt-3">
      {item.versionStartWeek != null ? (
        <p className="text-small text-ink-soft">
          {item.versionStartWeek}주차부터 새 질문으로 기록했습니다. 이전 질문의 답변과 직접 비교하지 않습니다.
        </p>
      ) : null}
      <div className="flex flex-col gap-6 pt-4">
        {weeks.map((week) => (
          <section key={week} className="print-flow border-t border-line pt-4">
            <h3 className="font-semibold">{week}주차</h3>
            <dl className="flex flex-col gap-4 pt-3">
              {observations.filter((o) => o.week === week).map((o) => (
                <div key={o.question} className="min-w-0">
                  <dt className="text-small text-ink-soft">{o.label}</dt>
                  <dd className="pt-1">
                    <span className="observation-value block" data-carried={o.source === 'CARRIED'}>
                      <span className="whitespace-pre-wrap break-words">{o.answers.join(' · ')}</span>
                      <span className="block text-small text-ink-soft">
                        {o.source === 'CARRIED' ? '지난 값 유지' : o.source === 'CONFIRMED' ? '직접 확인' : ''}
                      </span>
                    </span>
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
      </div>
    </div>
  );
}
