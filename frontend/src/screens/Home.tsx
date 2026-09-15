import { Link } from 'react-router';
import { getToken } from '../lib/api';
import { APP_NAME } from '../lib/constants';
import { dDayPhrase, formatDate, isVisitSoon } from '../lib/format';
import { densityPhrase, homeState, prepCardPhrase } from '../lib/home';
import { useMe, usePrepCard, useProgress } from '../lib/queries';
import type { Catalog } from '../lib/types';
import { Notice } from '../ui/Notice';
import { TherapistLinkPanel } from '../ui/TherapistLinkPanel';
import { AsyncState } from '../ui/AsyncState';
import { PageHeader } from '../ui/PageHeader';
import { Icon } from '../ui/Icon';
import { Landing } from './Landing';

export function Home({ catalog }: { catalog: Catalog }) {
  const hasToken = getToken() !== null;
  const meQ = useMe(hasToken);
  const recorded = meQ.data?.recordedThisWeek === true;
  const progressQ = useProgress(recorded);
  const visitSoon = isVisitSoon(meQ.data?.nextVisitDate ?? null, meQ.data?.today ?? '');
  const prepQ = usePrepCard(visitSoon);

  if (!hasToken) return <Landing />;
  if (!meQ.data) return <main className="app-page">
    <PageHeader title={APP_NAME} />
    <AsyncState kind={meQ.isError ? 'error' : 'loading'}
      message={meQ.isError ? '기록을 불러오지 못했습니다.' : '불러오는 중입니다…'}
      onRetry={() => { void meQ.refetch(); }} />
  </main>;

  const me = meQ.data;
  const state = homeState(hasToken, me, progressQ.data ?? null);
  const prep = prepQ.data;
  const prepPhrase = prep ? prepCardPhrase(prep.questions.length, prep.emptyMessage) : null;
  const density = densityPhrase(me);
  const progress = progressQ.data;

  const visit = me.nextVisitDate ? <section className={recorded && visitSoon ? 'visit-note note-surface' : 'visit-note compact-note'}>
    <div className="section-label"><Icon name="calendar" /><span>다음 진료</span></div>
    <h2 className="pt-3 text-[24px] font-semibold leading-snug">{visitSoon ? dDayPhrase(me.nextVisitDate, me.today) : formatDate(me.nextVisitDate)}</h2>
    {visitSoon && prepPhrase ? <p className="pt-3 text-ink-soft">{prepPhrase}</p> : null}
    {visitSoon && prepQ.isError ? <Notice role="alert">진료 준비 내용을 불러오지 못했습니다. <button className="text-link" onClick={() => { void prepQ.refetch(); }}>다시 시도하기</button></Notice> : null}
    {visitSoon ? <Link className={recorded ? 'btn mt-6' : 'text-link mt-2'} to="/prep-card">진료 준비 카드 보기</Link> : null}
  </section> : null;

  return <main className="app-page home-page">
    <div className="home-brand"><span className="brand-mark"><Icon name="notebook" /></span><PageHeader title={APP_NAME} settings focusKey="home" /></div>
    {recorded && visitSoon ? visit : null}
    <section data-testid="home-main" className="space-y-6">
      {state === 'NOT_RECORDED' ? <section className="note-surface action-note">
        <div className="section-label"><Icon name="notebook" /><span>이번 주 관찰</span></div>
        <h2 className="pt-4 text-title font-semibold leading-snug">이번 주 관찰을 남겨주세요</h2>
        <p className="pt-3 text-ink-soft">{me.fullRecheck ? `이번 주는 ${catalog.items.length}가지를 모두 여쭤봅니다` : `${me.week}주차 · 3분이면 됩니다`}</p>
        {me.fullRecheck ? <p className="text-small text-ink-soft">{me.week}주차</p> : null}
        <Link className="btn mt-7" to="/record">{me.fullRecheck ? `${catalog.items.length}가지 확인하기` : '3분 기록하기'}</Link>
      </section> : <div className="completion-note"><span className="completion-mark" aria-hidden="true">✓</span><div><p className="font-semibold">이번 주 기록을 남겼습니다</p><p className="text-small text-ink-soft">{me.week}주차</p></div></div>}

      {recorded && !progress ? <AsyncState kind={progressQ.isError ? 'error' : 'loading'} message={progressQ.isError ? '관찰 내용을 불러오지 못했습니다.' : '관찰 내용을 불러오는 중입니다…'} onRetry={() => { void progressQ.refetch(); }} /> : null}
      {recorded && progress && progressQ.isError ? <Notice role="alert">최신 관찰 내용을 불러오지 못했습니다. 이전 기록을 표시하고 있습니다. <button className="text-link" onClick={() => { void progressQ.refetch(); }}>다시 시도하기</button></Notice> : null}
      {recorded && progress && state === 'SILENT' ? <section className="note-surface quiet-note"><span className="icon-tile"><Icon name="notebook" /></span><h2 className="pt-5 text-[22px] font-semibold">이번 기간에는 바뀐 항목이 없습니다.</h2></section> : null}
      {recorded && progress && state === 'CHANGES' ? <>
        {progress.transitions.length ? <div className="compact-note space-y-3">{progress.transitions.map(t => <Notice key={`${t.item}-${t.axis}`}>{t.message}</Notice>)}</div> : null}
        {progress.changes.length ? <section className="note-surface observation-note">
          <h2 className="text-[24px] font-semibold">지켜보고 있는 변화</h2>
          <ul className="mt-5 divide-y divide-line border-t border-line">{progress.changes.map(c => <li key={`${c.item}-${c.axis}`} className="py-5 last:pb-0">
            <p className="font-semibold">{c.label}{c.axis !== 'LEVEL' ? <span className="font-normal"> · {c.axisLabel}</span> : null}</p>
            <p className="pt-2">{c.from} → {c.to}</p><p className="pt-2 text-small text-ink-soft">{c.message}</p>
          </li>)}</ul>
        </section> : null}
      </> : null}
    </section>
    {!recorded || !visitSoon ? visit : null}
    <section className="home-tools">
      {density ? <p className="text-small text-ink-soft">{density}</p> : null}
      <Link className="navigation-row" to="/trajectory"><span className="flex items-center gap-3"><Icon name="notebook" />전체 기록 보기</span><span aria-hidden="true">→</span></Link>
      <TherapistLinkPanel />
    </section>
  </main>;
}
