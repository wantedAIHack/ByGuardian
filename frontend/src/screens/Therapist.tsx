import { ScrollRegion } from '../ui/ScrollRegion';
import { ObservationValue } from '../ui/ObservationValue';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, api } from '../lib/api';
import { captureTherapistToken, clearTherapistToken } from '../lib/therapistToken';
import type { TherapistSummary } from '../lib/types';

export function Therapist() {
  const [token] = useState(captureTherapistToken);
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['therapist', token],
    queryFn: async () => {
      try {
        return await api.get<TherapistSummary>(`/t/${encodeURIComponent(token!)}`);
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) clearTherapistToken();
        throw error;
      }
    },
    enabled: token !== null,
    retry: false,
  });

  if (token !== null && isPending) return <main className="p-8"><p>불러오는 중입니다…</p></main>;
  if (token === null || isError || !data) {
    // 404(링크가 실제로 폐기됨)와 그 밖의 실패(네트워크 끊김 등 일시적 통신 장애)는 다른
    // 이야기다. 전자만 "새 주소를 받아주세요"가 맞다 — 후자에 같은 말을 하면, 한 번의
    // 통신 장애를 치료사에게 "링크가 죽었다"고 알리는 셈이고, 치료사의 합리적인 다음
    // 행동(새 링크 요청)이 finding 3을 거쳐 실제로 살아있던 링크를 죽인다. 후자는 접속
    // 자체가 안 됐다는 사실만 말한다(lib/catalog.tsx의 CatalogProvider 오류 문구와 같은
    // 어조) — 링크의 생사에 대해서는 아무 말도 하지 않는다.
    const isDead = token === null || (error instanceof ApiError && error.status === 404);
    return (
      <main className="p-8">
        {isDead ? (
          <p>이 주소는 더 이상 열리지 않습니다. 보호자분께 새 주소를 받아주세요.</p>
        ) : (
          <p>연결이 되지 않습니다. 잠시 후 다시 열어주세요.</p>
        )}
      </main>
    );
  }

  const changed = data.items.filter((i) => i.changed);
  const unchanged = data.items.filter((i) => !i.changed);

  return (
    <main className="therapist-page mx-auto max-w-6xl p-gutter text-body leading-relaxed">
      <header className="note-surface mb-8 border-t-4 border-t-accent">
        <h1 className="text-title font-semibold">가정 관찰 기록</h1>
        <p className="text-ink-soft">
          {data.weeks[0]}주차 ~ {data.weeks[data.weeks.length - 1]}주차 · {data.generatedAt.slice(0, 10)} 생성
        </p>
      </header>

      <section className="note-surface mt-6 min-w-0">
        <h2 className="font-semibold">주차별 관찰</h2>
        <ScrollRegion label="주차별 관찰 표">
          <table className="sticky-col min-w-max border-collapse">
            <thead>
              <tr>
                <th scope="col" className="border-b border-line px-3 py-2 text-left">항목</th>
                {data.weeks.map((w) => (
                  <th scope="col" key={w} className="border-b border-line px-3 py-2 text-left">{w}주</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {changed.map((item) =>
                item.axes.map((a) => (
                  <tr key={`${item.code}-${a.axis}`}>
                    <th scope="row" className="border-b border-line px-3 py-4 text-left font-normal align-top">
                      <span className="block font-semibold">{item.label}</span>
                      <span className="text-small text-ink-soft">{a.axisLabel}</span>
                    </th>
                    {data.weeks.map((w) => {
                      const p = a.values.find((v) => v.week === w);
                      return (
                        <td
                          key={w}
                          className="border-b border-line px-3 py-4 align-top"
                        >
                          <ObservationValue point={p} />
                        </td>
                      );
                    })}
                  </tr>
                )),
              )}
              {/* 변화 없는 항목은 한 줄로 접는다 */}
              {unchanged.length > 0 ? (
                <tr>
                  <td className="px-3 py-2 text-ink-soft" colSpan={data.weeks.length + 1}>
                    같은 기간 변화 없음 — {unchanged.map((i) => i.label).join(', ')}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </ScrollRegion>
        <p className="pt-4 text-small text-ink-soft">지난 값 유지: 달라진 것 없음으로 이어간 기록</p>
      </section>

      {data.signalsEnabled ? (
        <section className="note-surface mt-6 min-w-0">
          <h2 className="font-semibold">비언어 신호</h2>
          <ul className="pt-3">
            {data.signals.length === 0 ? (
              <li className="text-ink-soft">관찰된 신호 없음</li>
            ) : (
              data.signals.map((s) => (
                <li key={`${s.action}-${s.kind}`} className="py-1">
                  <span className="inline-block min-w-[180px]">{s.actionLabel} · {s.kindLabel}</span>
                  <span className="font-mono">
                    {data.weeks.map((w) => (s.weeks.includes(w) ? '●' : '·')).join(' ')}
                  </span>
                </li>
              ))
            )}
          </ul>
        </section>
      ) : null}

      {data.sleep.length > 0 ? (
        <section className="note-surface mt-6 min-w-0">
          <h2 className="font-semibold">야간 수면</h2>
          <ScrollRegion label="야간 수면 주차별 기록">
            <div className="flex min-w-max gap-6">
              {data.sleep.map((s) => (
                <div key={s.week}>
                  <p className="text-ink-faint">{s.week}주</p>
                  <p>{s.label}</p>
                </div>
              ))}
            </div>
          </ScrollRegion>
        </section>
      ) : null}

      {data.freeNotes.length > 0 ? (
        <section className="note-surface mt-6 min-w-0">
          <h2 className="font-semibold">보호자 기록 (원문)</h2>
          <ul className="flex flex-col gap-3 pt-3">
            {data.freeNotes.map((n) => (
              <li key={n.week}>
                <p className="text-ink-faint">
                  {n.week}주{n.timeTagLabel ? ` · ${n.timeTagLabel}` : ''}
                </p>
                {/* 원문 그대로. 자르지도 다듬지도 않는다. */}
                <p className="whitespace-pre-wrap">{n.text}</p>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {data.questions.length + data.extraQuestions.length > 0 ? (
        <section className="note-surface mt-6 min-w-0">
          <h2 className="font-semibold">보호자가 여쭤보고 싶은 것</h2>
          <ul className="list-disc pt-3 pl-5">
            {data.questions.map((q) => <li key={q}>{q}</li>)}
            {data.extraQuestions.map((q) => <li key={q}>{q}</li>)}
          </ul>
        </section>
      ) : null}

      <section className="note-surface mt-6 min-w-0">
        <h2 className="font-semibold">기록 밀도</h2>
        <p className="pt-2">
          {data.density.totalWeeks}주 중 {data.density.recordedWeeks}주 기록
          {' · '}그중 {data.density.confirmedWeeks}주는 직접 확인한 값
        </p>
        <p className="text-ink-soft">작성자 — {data.density.authors.join(', ')}</p>
        {data.authorChanges.map((c) => (
          <p key={c.week} className="text-ink-soft">
            {c.week}주차부터 {c.to}이(가) 작성 (이전 {c.from})
          </p>
        ))}
      </section>

      <footer className="mt-10 border-t border-line pt-4 text-ink-soft">
        {/* 서버가 준 문단이다. 여기에 요약이나 판정을 덧붙이지 않는다. */}
        <p>{data.disclaimer}</p>
      </footer>
    </main>
  );
}
