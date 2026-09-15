import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Home } from './Home';
import { catalogFixture, me } from '../test/fixtures';
import { setToken } from '../lib/api';
import { APP_NAME } from '../lib/constants';
import type { Me, PrepCard, Progress } from '../lib/types';

const BASE = 'http://localhost:8080';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function serve(opts: { me?: Me; progress?: Progress; prepCard?: PrepCard }) {
  const handlers = [];
  if (opts.me) handlers.push(http.get(`${BASE}/me`, () => HttpResponse.json(opts.me)));
  if (opts.progress) handlers.push(http.get(`${BASE}/me/progress`, () => HttpResponse.json(opts.progress)));
  if (opts.prepCard) handlers.push(http.get(`${BASE}/me/prep-card`, () => HttpResponse.json(opts.prepCard)));
  server.use(...handlers);
}

function renderHome() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // 카탈로그는 라우트가 useCatalog()로 받아 prop으로 내린다(routes.tsx의 HomeRoute).
  // 화면 테스트는 CatalogProvider의 비동기 로딩을 떠안지 않는다 — Onboarding.test.tsx와 같다.
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Home catalog={catalogFixture} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

const silent: Progress = { week: 6, silent: true, changes: [], transitions: [], questions: [] };

describe('홈', () => {
  it('경과 요청 실패를 변화 없음으로 표시하지 않는다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: true }) });
    server.use(http.get(`${BASE}/me/progress`, () => HttpResponse.json({}, { status: 503 })));
    renderHome();
    expect(await screen.findByRole('alert')).toHaveTextContent('관찰 내용을 불러오지 못했습니다.');
    expect(screen.queryByText('이번 기간에는 바뀐 항목이 없습니다.')).not.toBeInTheDocument();
  });
  it('토큰이 없으면 시작하기·이어받기·둘러보기를 보여준다', () => {
    renderHome();
    expect(screen.getByRole('link', { name: '관찰 기록 시작하기' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /이어받기/ })).toBeInTheDocument();
    // 심사위원이 온보딩 열여덟 화면을 거치지 않고 데이터 있는 화면을 볼 수 있는 유일한 통로.
    expect(screen.getByRole('link', { name: '먼저 둘러보기' })).toHaveAttribute('href', '/demo');
  });

  it('판정 문구를 만들지 않는다 — 토큰 없음', () => {
    const { container } = renderHome();
    // 금지어 나열이 아니라 전체를 화이트리스트로 건다(Recover.test.tsx·Settings.test.tsx와
    // 같은 방식). 둘러보기 링크를 더한 뒤로 이 문자열이 바뀌었다 — 그것이 이 assertion이
    // 실제로 일하고 있다는 증거지, 완화할 이유가 아니다.
    expect(container.textContent).toBe(
      [
        APP_NAME,
        '기존 기록 이어받기 ↗',
        '보호자의 일상 관찰 노트',
        '집에서의 관찰을,다음 진료의 질문으로',
        '집에서 보신 것을 남겨두시면, 다음에 병원 가실 때 여쭤볼 것을 만들어 드립니다.',
        '관찰 기록 시작하기 →', '먼저 둘러보기 ↗',
        '나의 관찰 노트예시집에서 본 장면을남겨요',
        '관찰한 내용을 모아 진료실에서 여쭤볼 질문을 준비합니다.',
        '기록에서 질문으로,질문에서 대화로.',
        '01관찰 남기기02질문 준비하기03진료실에서 함께 보기',
      ].join(''),
    );
    // 눈에 띄는 행동은 하나뿐이어야 한다 — 둘러보기가 조용히 두 번째 .btn을 만들지 않았는지 확인한다.
    const btns = container.querySelectorAll('.btn');
    expect(btns).toHaveLength(1);
    expect(btns[0]?.textContent).toBe('관찰 기록 시작하기 →');
  });

  it('다시 온 보호자에게 시작 화면을 번쩍이지 않는다', async () => {
    setToken('t');
    // /me 를 붙들어 두어 로딩 상태를 그대로 본다
    server.use(http.get(`${BASE}/me`, () => new Promise(() => {})));
    renderHome();

    expect(screen.getByText('불러오는 중입니다…')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '관찰 기록 시작하기' })).not.toBeInTheDocument();
  });

  it('이번 주 기록이 없으면 기록 버튼 하나만 크게 둔다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: false, week: 6 }) });
    renderHome();

    expect(await screen.findByRole('link', { name: '3분 기록하기' })).toBeInTheDocument();
    expect(screen.getByText(/6주차/)).toBeInTheDocument();
  });

  it('전체 재확인 주는 미리 알린다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: false, week: 8, fullRecheck: true }) });
    renderHome();

    expect(await screen.findByText('이번 주는 8가지를 모두 여쭤봅니다')).toBeInTheDocument();
  });

  it('재확인 안내의 개수를 카탈로그에서 끌어온다', async () => {
    // 표본의 항목 수가 마침 8이라 상수로 박아도 이 스위트는 통과한다.
    // 다섯 항목 표본으로 카탈로그에서 끌어오는지를 고정한다.
    const short = { ...catalogFixture, items: catalogFixture.items.slice(0, 5) };
    setToken('t');
    serve({ me: me({ recordedThisWeek: false, week: 8, fullRecheck: true }) });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter><Home catalog={short} /></MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('이번 주는 5가지를 모두 여쭤봅니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '5가지 확인하기' })).toBeInTheDocument();
  });

  it('침묵 상태의 여백을 채우지 않는다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: true }), progress: silent });
    renderHome();

    const main = await screen.findByTestId('home-main');
    // 금지어 나열이 아니라 전체를 화이트리스트로 건다. 침묵 상태에 스트릭 문구든
    // 뭐든 한 글자라도 더 넣으면 이 assertion이 걸린다 — 그 여백이 이 제품의 주장이다.
    await screen.findByText('이번 기간에는 바뀐 항목이 없습니다.');
    expect(main.textContent).toBe('✓이번 주 기록을 남겼습니다6주차이번 기간에는 바뀐 항목이 없습니다.');
  });

  it('변화는 서버 문장을 그대로 보여준다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true }),
      progress: {
        week: 6, silent: false, transitions: [], questions: [],
        changes: [{
          item: 'toilet', label: '화장실 이용', axis: 'LEVEL', axisLabel: '도움 수준',
          status: 'SUSTAINED', duration: 4, from: '지켜보면 됨', to: '혼자 하심',
          message: '이 변화가 4주째 유지되고 있습니다.',
        }],
      },
    });
    renderHome();

    expect(await screen.findByText('지켜보고 있는 변화')).toBeInTheDocument();
    expect(screen.getByText('화장실 이용')).toBeInTheDocument();
    expect(screen.getByText('지켜보면 됨 → 혼자 하심')).toBeInTheDocument();
    expect(screen.getByText('이 변화가 4주째 유지되고 있습니다.')).toBeInTheDocument();
  });

  it('변화 없이 전환만 있어도 침묵이 아니다 — 서버 문장을 그대로 보여준다', async () => {
    // ProgressService는 changes와 transitions를 항목·축마다 배타적으로 채운다. SUSTAINED가
    // FLUCTUATING으로 바뀌는 주는 changes가 비고 transitions만 채워진다(ProgressControllerTest.
    // transitionAppearsTheWeekSustainedTurnsFluctuatingThenDisappears, week 5) — 이 payload가
    // 그 주다. changes만 보고 SILENT로 묶으면 이 문장이 화면에서 통째로 사라진다.
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true }),
      progress: {
        week: 5, silent: false, changes: [], questions: [],
        transitions: [{
          item: 'toilet', label: '화장실 이용', axis: 'LEVEL',
          message: '2주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다.',
        }],
      },
    });
    renderHome();

    expect(await screen.findByText(
      '2주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다.',
    )).toBeInTheDocument();
    // changes가 비었으니 '지켜보고 있는 변화' 틀(제목+목록)은 만들지 않는다 — 없는 목록을
    // 소개하지 않는다. 침묵 화면의 문구도 나오면 안 된다 — 이 주는 침묵이 아니다.
    expect(screen.queryByText('지켜보고 있는 변화')).not.toBeInTheDocument();
    expect(screen.queryByText('이번 기간에는 바뀐 항목이 없습니다.')).not.toBeInTheDocument();
  });

  it('외래가 가까우면 준비 카드가 맨 위로 온다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true, nextVisitDate: '2026-09-08', today: '2026-09-06' }),
      progress: silent,
      prepCard: {
        week: 6, nextVisitDate: '2026-09-08', extraQuestions: [], emptyMessage: null,
        therapistGlance: [],
        questions: [
          { rank: 1, type: 'PLATEAU', sentence: '왜 안 늘고 있을까요?', source: 'engine',
            evidence: { items: [], signal: null } },
          { rank: 2, type: 'PLATEAU', sentence: '더 여쭤볼 것', source: 'engine',
            evidence: { items: [], signal: null } },
        ],
      },
    });
    renderHome();

    expect(await screen.findByText('9월 8일 진료가 있습니다 (모레)')).toBeInTheDocument();
    // me → visitSoon → prep-card는 순서가 있는 요청이라(외래가 가까운지는 me가 있어야 안다),
    // d-day 문구가 뜬 시점과 준비 카드 문구가 뜨는 시점 사이에 렌더가 한 번 더 끼어든다.
    // 그래서 이 문구도 findByText로 기다린다. getByText로 동기 단정하면 브리프대로도 깨진다.
    expect(await screen.findByText('여쭤볼 것 2가지를 준비했습니다.')).toBeInTheDocument();
    // 질문 문장 자체는 홈에 내지 않는다. 근거와 함께 봐야 뜻이 산다.
    expect(screen.queryByText('왜 안 늘고 있을까요?')).not.toBeInTheDocument();
  });

  it('기록 전에는 준비 카드가 눈에 띄는 행동을 뺏지 않는다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: false, nextVisitDate: '2026-09-08', today: '2026-09-06' }),
      prepCard: {
        week: 6, nextVisitDate: '2026-09-08', questions: [], extraQuestions: [],
        emptyMessage: '이번에는 특별히 여쭤볼 것이 없습니다.', therapistGlance: [],
      },
    });
    renderHome();

    const record = await screen.findByRole('link', { name: '3분 기록하기' });
    const prep = screen.getByRole('link', { name: '진료 준비 카드 보기' });
    expect(record.className).toContain('btn');
    expect(prep.className).not.toContain('btn');
  });

  it('질문이 없으면 서버의 빈 문구를 그대로 쓴다', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true, nextVisitDate: '2026-09-08', today: '2026-09-06' }),
      progress: silent,
      prepCard: {
        week: 6, nextVisitDate: '2026-09-08', questions: [], extraQuestions: [],
        emptyMessage: '이번에는 특별히 여쭤볼 것이 없습니다.', therapistGlance: [],
      },
    });
    renderHome();

    expect(await screen.findByText('이번에는 특별히 여쭤볼 것이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByText(/가지를 준비했습니다/)).not.toBeInTheDocument();
  });

  it('조용한 목록이 늘 아래에 있다', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: true, recordedWeeks: 5, totalWeeks: 6 }), progress: silent });
    renderHome();

    expect(await screen.findByText('지금까지 6주 중 5주 기록')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '전체 기록 보기' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '설정' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '치료사에게 보여드리기' })).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  // 판정 문구 화이트리스트 — 도달 가능한 상태마다 하나(Settings.test.tsx·Therapist.test.tsx와
  // 같은 정책). 금지어 나열이 아니라 렌더된 전체를 화이트리스트로 건다 — 목록에 없는 말이
  // 한 글자라도 끼어들면 아래 assertion들이 걸린다.
  // ------------------------------------------------------------------

  it('판정 문구를 만들지 않는다 — 기록 전 상태', async () => {
    setToken('t');
    serve({ me: me({ recordedThisWeek: false, week: 6 }) });
    renderHome();

    const main = await screen.findByTestId('home-main');
    expect(main.textContent).toBe(
      ['이번 주 관찰', '이번 주 관찰을 남겨주세요', '6주차 · 3분이면 됩니다', '3분 기록하기'].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — 변화가 있는 상태', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true }),
      progress: {
        week: 6, silent: false, questions: [],
        // changes와 transitions를 함께 채운다 — finding 1 이후로 한 주에 둘 다 있을 수
        // 있다(ProgressService가 항목·축마다 배타적으로 채우지, 주 단위로 배타적이지 않다).
        transitions: [{
          item: 'ambulation', label: '집 안에서 걷기', axis: 'LEVEL',
          message: '2주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다.',
        }],
        changes: [{
          item: 'toilet', label: '화장실 이용', axis: 'LEVEL', axisLabel: '도움 수준',
          status: 'SUSTAINED', duration: 4, from: '지켜보면 됨', to: '혼자 하심',
          message: '이 변화가 4주째 유지되고 있습니다.',
        }],
      },
    });
    renderHome();

    const main = await screen.findByTestId('home-main');
    await screen.findByText('지켜보고 있는 변화');
    expect(main.textContent).toBe(
      [
        '✓이번 주 기록을 남겼습니다6주차',
        '2주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다.',
        '지켜보고 있는 변화',
        '화장실 이용',
        '지켜보면 됨 → 혼자 하심',
        '이 변화가 4주째 유지되고 있습니다.',
      ].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — 외래가 가까운 상태(준비 카드 배너)', async () => {
    setToken('t');
    serve({
      me: me({ recordedThisWeek: true, nextVisitDate: '2026-09-08', today: '2026-09-06' }),
      progress: silent,
      prepCard: {
        week: 6, nextVisitDate: '2026-09-08', extraQuestions: [], emptyMessage: null,
        therapistGlance: [],
        questions: [
          { rank: 1, type: 'PLATEAU', sentence: '왜 안 늘고 있을까요?', source: 'engine',
            evidence: { items: [], signal: null } },
          { rank: 2, type: 'PLATEAU', sentence: '더 여쭤볼 것', source: 'engine',
            evidence: { items: [], signal: null } },
        ],
      },
    });
    renderHome();

    const main = await screen.findByRole('main');
    await screen.findByText('9월 8일 진료가 있습니다 (모레)');
    await screen.findByText('여쭤볼 것 2가지를 준비했습니다.');
    // 배너가 맨 위로 오는 동안에도(상태 E) 하단은 그대로 붙어 있다 — visitSoon이 참이라
    // '다음 진료' 줄만 하단에서 빠진다(배너가 이미 그 날짜를 말했다).
    expect(main.textContent).toBe(
      [
        APP_NAME, '설정', '다음 진료',
        '9월 8일 진료가 있습니다 (모레)',
        '여쭤볼 것 2가지를 준비했습니다.',
        '진료 준비 카드 보기',
        '✓이번 주 기록을 남겼습니다6주차',
        '이번 기간에는 바뀐 항목이 없습니다.',
        '지금까지 6주 중 5주 기록',
        '전체 기록 보기→',
        '치료사에게 보여드리기',
      ].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — 조용한 하단', async () => {
    setToken('t');
    // 외래가 사흘 밖이라 배너는 없다 — 그래서 하단에 '다음 진료' 줄이 대신 나온다.
    // 이 조합(배너 없이 다음 진료 날짜 + 밀도)은 위 세 화이트리스트 어디에도 없다.
    serve({
      me: me({
        recordedThisWeek: true, nextVisitDate: '2026-10-01', today: '2026-09-06',
        recordedWeeks: 5, totalWeeks: 6,
      }),
      progress: silent,
    });
    renderHome();

    const main = await screen.findByRole('main');
    await screen.findByText('10월 1일');
    await screen.findByText('이번 기간에는 바뀐 항목이 없습니다.');
    expect(main.textContent).toBe(
      [
        APP_NAME, '설정',
        '✓이번 주 기록을 남겼습니다6주차',
        '이번 기간에는 바뀐 항목이 없습니다.',
        '다음 진료10월 1일',
        '지금까지 6주 중 5주 기록',
        '전체 기록 보기→',
        '치료사에게 보여드리기',
      ].join(''),
    );
  });
});
