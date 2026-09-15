import { ScrollRegion } from '../ui/ScrollRegion';
import { ObservationValue } from '../ui/ObservationValue';
import { useEffect, useRef, useState } from 'react';
import { PageHeader } from '../ui/PageHeader';
import { AsyncState } from '../ui/AsyncState';
import { formatDate } from '../lib/format';
import { usePrepCard, useSaveExtra } from '../lib/queries';
import { Button } from '../ui/Button';
import { Collapse } from '../ui/Collapse';
import { Notice } from '../ui/Notice';
import { TherapistLinkPanel } from '../ui/TherapistLinkPanel';

const MAX_EXTRA = 5;
const MAX_LEN = 200;

export function PrepCard() {
  const query = usePrepCard();
  const { data } = query;
  const saveExtra = useSaveExtra();
  const [extra, setExtra] = useState<string[]>([]);
  const [dirty, setDirty] = useState(false);
  const editVersion = useRef(0);

  useEffect(() => {
    if (data && !dirty) setExtra(data.extraQuestions);
  }, [data, dirty]);

  if (!data) return <main className="app-page"><PageHeader title="진료 준비" backTo="/" />
    <AsyncState kind={query.isError ? 'error' : 'loading'} message={query.isError ? '진료 질문을 불러오지 못했습니다.' : '불러오는 중입니다…'}
      onRetry={query.isError ? () => { void query.refetch(); } : undefined} /></main>;

  const edit = (i: number, v: string) => {
    editVersion.current++;
    setDirty(true);
    setExtra((prev) => prev.map((x, j) => (j === i ? v.slice(0, MAX_LEN) : x)));
  };

  return (
    <main className="app-page space-y-8">
      <PageHeader backTo="/" title={data.nextVisitDate ? `${formatDate(data.nextVisitDate)} 진료` : '진료 준비'} focusKey="prep" />

      {/* 질문은 최대 3개이고 0개일 수 있다. 빈 칸을 만들지 않는다.
          질문과 그 '근거 보기' 사이 간격이 질문끼리의 간격과 거의 같아서 근거가
          어느 질문에 붙은 것인지 알 수 없었다. 근접성이 그룹을 만들도록 항목
          안은 좁히고 항목 사이는 선으로 끊는다. */}
      {data.questions.length === 0 ? (
        data.emptyMessage ? <p className="pt-8">{data.emptyMessage}</p> : null
      ) : (
        <ol className="space-y-5">
          {data.questions.map((q) => (
            <li key={q.rank} className="min-w-0">
              {/* rank 접두사를 별도 노드로 둔다: q.sentence가 p 자신의 유일한 텍스트 노드로
                  남아야 '서버 문장을 그대로 낸다'가 정확히 그 문장만으로 검증된다. */}
              <article className="note-surface" aria-labelledby={`question-${q.rank}`}>
              <p className="mb-2 text-small text-ink-soft">질문 {q.rank}</p>
              <h2 id={`question-${q.rank}`} className="text-[22px] font-semibold leading-snug">{q.sentence}</h2>
              <div className="pt-1">
                <Collapse label="이 질문의 관찰 근거">
                  <div className="flex flex-col gap-4">
                    {q.evidence.items.map((it) => (
                      <div key={`${it.code}-${it.axis}`}>
                        <p className="text-small text-ink-soft">{it.label} · {it.axisLabel}</p>
                        <ScrollRegion label={`${it.label} ${it.axisLabel} 주차별 기록`}>
                          <div className="flex min-w-max gap-5 pt-1">
                            {it.values.map((p) => (
                              <div key={p.week} className="min-w-[92px]">
                                <p className="text-small text-ink-faint">{p.week}주</p>
                                <ObservationValue point={p} />
                              </div>
                            ))}
                          </div>
                        </ScrollRegion>
                      </div>
                    ))}
                    {q.evidence.signal ? (
                      <p className="text-small text-ink-soft">
                        {q.evidence.signal.actionLabel} · {q.evidence.signal.kindLabel} —{' '}
                        {q.evidence.signal.weeks.join('주, ')}주
                      </p>
                    ) : null}
                  </div>
                </Collapse>
              </div>
              </article>
            </li>
          ))}
        </ol>
      )}

      <section className="note-surface">
        <h2 className="font-semibold">내가 더 여쭤보고 싶은 것</h2>
        <div className="flex flex-col gap-3 pt-4">
          {extra.map((q, i) => (
            <label key={i} className="block">
              {/* 칸마다 같은 이름이면 스크린 리더가 여러 칸을 구분하지 못한다. 순번을 붙인다. */}
              <span className="sr-only">여쭤보고 싶은 것 {i + 1}</span>
              <input
                aria-label={`여쭤보고 싶은 것 ${i + 1}`}
                className="min-h-[56px] w-full rounded-lg border border-control bg-paper px-4"
                value={q}
                maxLength={MAX_LEN}
                onChange={(e) => edit(i, e.target.value)}
              />
            </label>
          ))}
        </div>
        <div className="flex flex-col gap-3 pt-4">
          {extra.length < MAX_EXTRA ? (
            <Button
              variant="plain"
              onClick={() => { editVersion.current++; setDirty(true); setExtra((p) => [...p, '']); }}
            >
              + 추가
            </Button>
          ) : (
            <Notice>다섯 개까지 넣으실 수 있습니다.</Notice>
          )}
          {dirty ? (
            <Button
              disabled={saveExtra.isPending}
              onClick={() => {
                const submittedVersion = editVersion.current;
                saveExtra.mutate(
                  extra.map((x) => x.trim()).filter((x) => x.length > 0),
                  { onSuccess: () => {
                    if (submittedVersion === editVersion.current) setDirty(false);
                  } },
                );
              }}
            >
              {saveExtra.isPending ? '저장하는 중입니다…' : '저장'}
            </Button>
          ) : null}
        </div>
        {saveExtra.isError ? <Notice role="alert">질문을 저장하지 못했습니다. 입력한 내용은 그대로 있습니다. 다시 저장해 주세요.</Notice> : null}
        {saveExtra.isSuccess && !dirty ? <Notice role="status">질문을 저장했습니다.</Notice> : null}
      </section>

      {data.therapistGlance.length > 0 ? (
        <section className="note-surface">
          <h2 className="font-semibold">진료실에서 보여드릴 요약</h2>
          <ul className="flex flex-col gap-2 pt-3">
            {data.therapistGlance.map((g) => (
              <li key={g} className="text-ink-soft">{g}</li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className="pt-10">
        <TherapistLinkPanel />
      </div>
    </main>
  );
}
