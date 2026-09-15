import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { formatDate } from '../lib/format';
import { usePrepCard, useSaveExtra } from '../lib/queries';
import { Button } from '../ui/Button';
import { Collapse } from '../ui/Collapse';
import { Notice } from '../ui/Notice';
import { TherapistLinkPanel } from '../ui/TherapistLinkPanel';

const MAX_EXTRA = 5;
const MAX_LEN = 200;

export function PrepCard() {
  const { data, isPending } = usePrepCard();
  const saveExtra = useSaveExtra();
  const [extra, setExtra] = useState<string[]>([]);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (data && !dirty) setExtra(data.extraQuestions);
  }, [data, dirty]);

  if (isPending || !data) {
    return <main className="mx-auto max-w-lg px-gutter py-10"><p>불러오는 중입니다…</p></main>;
  }

  const edit = (i: number, v: string) => {
    setDirty(true);
    setExtra((prev) => prev.map((x, j) => (j === i ? v.slice(0, MAX_LEN) : x)));
  };

  return (
    <main className="mx-auto max-w-lg px-gutter py-10">
      <Link
        className="inline-flex min-h-[48px] items-center text-small text-ink-soft underline"
        to="/"
      >
        ← 홈
      </Link>
      <h1 className="pt-6 text-title font-semibold">
        {data.nextVisitDate ? `${formatDate(data.nextVisitDate)} 진료` : '진료 준비'}
      </h1>

      {/* 질문은 최대 3개이고 0개일 수 있다. 빈 칸을 만들지 않는다. */}
      {data.questions.length === 0 ? (
        data.emptyMessage ? <p className="pt-8">{data.emptyMessage}</p> : null
      ) : (
        <ol className="flex flex-col gap-10 pt-8">
          {data.questions.map((q) => (
            <li key={q.rank}>
              {/* rank 접두사를 별도 노드로 둔다: q.sentence가 p 자신의 유일한 텍스트 노드로
                  남아야 '서버 문장을 그대로 낸다'가 정확히 그 문장만으로 검증된다. */}
              <p className="font-semibold"><span>{q.rank}. </span>{q.sentence}</p>
              <div className="pt-3">
                <Collapse label="근거 보기">
                  <div className="flex flex-col gap-4">
                    {q.evidence.items.map((it) => (
                      <div key={`${it.code}-${it.axis}`}>
                        <p className="text-small text-ink-soft">{it.label} · {it.axisLabel}</p>
                        <div className="scroll-hint overflow-x-auto">
                          <div className="flex min-w-max gap-5 pt-1">
                            {it.values.map((p) => (
                              <div key={p.week} className="min-w-[92px]">
                                <p className="text-small text-ink-faint">{p.week}주</p>
                                <p className={p.source === 'CARRIED' ? 'text-ink-faint' : ''}>
                                  {p.label}
                                </p>
                              </div>
                            ))}
                          </div>
                        </div>
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
            </li>
          ))}
        </ol>
      )}

      <section className="pt-14">
        <h2 className="font-semibold">내가 더 여쭤보고 싶은 것</h2>
        <div className="flex flex-col gap-3 pt-4">
          {extra.map((q, i) => (
            <label key={i} className="block">
              {/* 칸마다 같은 이름이면 스크린 리더가 여러 칸을 구분하지 못한다. 순번을 붙인다. */}
              <span className="sr-only">여쭤보고 싶은 것 {i + 1}</span>
              <input
                aria-label={`여쭤보고 싶은 것 ${i + 1}`}
                className="min-h-[56px] w-full rounded-lg border border-line px-4"
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
              onClick={() => { setDirty(true); setExtra((p) => [...p, '']); }}
            >
              + 추가
            </Button>
          ) : (
            <Notice>다섯 개까지 넣으실 수 있습니다.</Notice>
          )}
          {dirty ? (
            <Button
              disabled={saveExtra.isPending}
              onClick={() =>
                saveExtra.mutate(
                  extra.map((x) => x.trim()).filter((x) => x.length > 0),
                  { onSuccess: () => setDirty(false) },
                )
              }
            >
              {saveExtra.isPending ? '저장하는 중입니다…' : '저장'}
            </Button>
          ) : null}
        </div>
      </section>

      {data.therapistGlance.length > 0 ? (
        <section className="pt-14">
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
