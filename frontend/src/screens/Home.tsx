import { Link } from 'react-router';
import { getToken } from '../lib/api';
import { APP_NAME } from '../lib/constants';
import { dDayPhrase, formatDate, isVisitSoon } from '../lib/format';
import { densityPhrase, homeState, prepCardPhrase } from '../lib/home';
import { useMe, usePrepCard, useProgress } from '../lib/queries';
import type { Catalog } from '../lib/types';
import { Notice } from '../ui/Notice';
import { TherapistLinkPanel } from '../ui/TherapistLinkPanel';

export function Home({ catalog }: { catalog: Catalog }) {
  // 토큰이 바뀌는 순간은 온보딩·이어받기 뒤의 이동뿐이라 이동이 곧 재렌더다.
  const hasToken = getToken() !== null;

  const me = useMe(hasToken).data ?? null;

  // 이번 주 기록이 없으면 경과를 부를 이유가 없다. 준비 카드도 외래가 가까울 때만 부른다.
  const recorded = me?.recordedThisWeek === true;
  const progress = useProgress(recorded).data ?? null;

  const visitSoon = isVisitSoon(me?.nextVisitDate ?? null, me?.today ?? '');
  const prep = usePrepCard(visitSoon).data ?? null;

  const state = homeState(hasToken, me, progress);

  if (state === 'ONBOARDING') {
    return (
      <main className="mx-auto max-w-lg px-gutter py-12">
        <h1 className="text-title font-semibold">{APP_NAME}</h1>
        <p className="pt-6">
          집에서 보신 것을 남겨두시면, 다음에 병원 가실 때 여쭤볼 것을 만들어 드립니다.
        </p>
        <div className="pt-10">
          <Link className="btn" to="/onboarding">시작하기</Link>
        </div>
        <p className="pt-6 text-center text-small">
          <Link className="inline-flex min-h-[48px] items-center text-ink-soft underline" to="/recover">
            이미 쓰고 계신가요? 이어받기
          </Link>
        </p>
      </main>
    );
  }

  if (state === 'LOADING' || !me) {
    return <main className="mx-auto max-w-lg px-gutter py-12"><p>불러오는 중입니다…</p></main>;
  }

  const prepPhrase = prep ? prepCardPhrase(prep.questions.length, prep.emptyMessage) : null;
  const density = densityPhrase(me);

  return (
    <main className="mx-auto flex min-h-full max-w-lg flex-col px-gutter py-10">
      {/* 상태 E — 외래가 사흘 안쪽이면 준비 카드가 맨 위로 온다 */}
      {visitSoon && me.nextVisitDate ? (
        <section className="pb-10">
          <p className="font-semibold">{dDayPhrase(me.nextVisitDate, me.today)}</p>
          {prepPhrase ? <p className="pt-3">{prepPhrase}</p> : null}
          <div className="pt-5">
            {/* 기록이 우선이다: 이번 주 기록이 없으면 준비 카드는 눈에 띄는 행동을 뺏지 않는다.
                기록이 들어와야 카드도 이번 주를 반영하니, 먼저 보내면 낡은 카드를 보여주고
                정작 할 일은 안 한 채로 둔다. */}
            {state === 'NOT_RECORDED' ? (
              <Link
                className="inline-flex min-h-[48px] items-center text-accent underline"
                to="/prep-card"
              >
                진료 준비 카드 보기
              </Link>
            ) : (
              <Link className="btn" to="/prep-card">진료 준비 카드 보기</Link>
            )}
          </div>
        </section>
      ) : null}

      <section className="flex-1" data-testid="home-main">
        {state === 'NOT_RECORDED' ? (
          <>
            <h1 className="text-title font-semibold">이번 주 관찰을 남겨주세요</h1>
            <p className="pt-3 text-ink-soft">
              {me.fullRecheck
                ? `이번 주는 ${catalog.items.length}가지를 모두 여쭤봅니다`
                : `${me.week}주차 · 3분이면 됩니다`}
            </p>
            {me.fullRecheck ? <p className="text-small text-ink-faint">{me.week}주차</p> : null}
            <div className="pt-8">
              <Link className="btn" to="/record">
                {me.fullRecheck ? `${catalog.items.length}가지 확인하기` : '3분 기록하기'}
              </Link>
            </div>
          </>
        ) : null}

        {state === 'SILENT' ? (
          <>
            <p className="font-semibold">이번 주 기록을 남기셨어요 ✓</p>
            <p className="pt-6">이번 기간에는 바뀐 항목이 없습니다.</p>
            {/* 여백을 채우지 않는다. 여기에 무엇이든 넣고 싶은 충동이 이 제품을 망친다. */}
          </>
        ) : null}

        {state === 'CHANGES' && progress ? (
          <>
            <p className="font-semibold">이번 주 기록을 남기셨어요 ✓</p>
            {progress.transitions.length > 0 ? (
              <div className="pt-6">
                {progress.transitions.map((t) => (
                  <Notice key={`${t.item}-${t.axis}`}>{t.message}</Notice>
                ))}
              </div>
            ) : null}
            <h2 className="pt-8 text-title font-semibold">지켜보고 있는 변화</h2>
            <ul className="flex flex-col gap-8 pt-6">
              {progress.changes.map((c) => (
                <li key={`${c.item}-${c.axis}`}>
                  <p className="font-semibold">
                    {c.label}
                    {c.axis === 'LEVEL' ? null : <span className="font-normal"> · {c.axisLabel}</span>}
                  </p>
                  <p className="pt-1">{c.from} → {c.to}</p>
                  {/* 서버가 만든 문장이다. 자르지도 덧붙이지도 않는다. */}
                  <p className="pt-1 text-ink-soft">{c.message}</p>
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </section>

      <section className="flex flex-col gap-4 pt-16 text-small text-ink-soft">
        {me.nextVisitDate && !visitSoon ? (
          <p>다음 진료 · {formatDate(me.nextVisitDate)}</p>
        ) : null}
        {density ? <p>{density}</p> : null}
        <Link className="inline-flex min-h-[48px] items-center text-ink-soft underline" to="/trajectory">전체 기록 보기</Link>
        <TherapistLinkPanel />
        <Link className="inline-flex min-h-[48px] items-center text-ink-soft underline" to="/settings">설정</Link>
      </section>
    </main>
  );
}
