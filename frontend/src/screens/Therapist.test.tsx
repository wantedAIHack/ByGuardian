import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Therapist } from './Therapist';
import type { Trajectory, TherapistSummary } from '../lib/types';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const summary: TherapistSummary = {
  generatedAt: '2026-09-06T10:00:00+09:00',
  weeks: [4, 5, 6],
  items: [
    {
      code: 'toilet', label: '화장실 이용', changed: true,
      axes: [{
        axis: 'LEVEL', axisLabel: '도움 수준',
        values: [
          { week: 4, value: 1, label: '손 잡아드림', source: 'CONFIRMED' },
          { week: 5, value: 2, label: '지켜보면 됨', source: 'CARRIED' },
          { week: 6, value: 3, label: '혼자 하심', source: 'CONFIRMED' },
        ],
      }],
    },
    {
      code: 'bathing', label: '목욕', changed: false,
      axes: [{
        axis: 'LEVEL', axisLabel: '도움 수준',
        values: [{ week: 6, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
      }],
    },
  ],
  signals: [
    { action: 'TRANSFER', actionLabel: '옮겨 앉을 때', kind: 'GRIMACE', kindLabel: '찡그림', weeks: [5, 6] },
  ],
  sleep: [
    { week: 4, value: 2, label: '잘 주무심' },
    { week: 5, value: 1, label: '가끔 깨심' },
    { week: 6, value: 1, label: '가끔 깨심' },
  ],
  signalsEnabled: true,
  freeNotes: [
    { week: 6, text: '아침에 혼자 화장실 다녀오셨어요.', timeTag: 'MORNING', timeTagLabel: '아침' },
  ],
  questions: ['집 안에서 걷기는 왜 안 늘고 있을까요?'],
  extraQuestions: ['밤에 자주 깨시는데 괜찮은가요?'],
  density: { totalWeeks: 6, recordedWeeks: 5, confirmedWeeks: 3, authors: ['딸', '아들'] },
  authorChanges: [{ week: 5, from: '딸', to: '아들' }],
  disclaimer: '이 기록은 보호자가 가정에서 관찰한 내용입니다. 진단이나 평가가 아닙니다.',
};

// 두 축을 가진 항목 — 표의 ai>0 분기(축마다 한 줄로 나뉘되 항목 라벨과 ' · '
// 연결어는 첫 줄에만 붙는다)를 잡는다. summary의 항목은 전부 축이 하나뿐이라
// 이 분기를 타지 않는다.
const twoAxisItem: Trajectory = {
  code: 'dressing', label: '옷 입기', changed: true,
  axes: [
    {
      axis: 'LEVEL', axisLabel: '도움 수준',
      values: [
        { week: 4, value: 1, label: '손 잡아드림', source: 'CONFIRMED' },
        { week: 5, value: 1, label: '손 잡아드림', source: 'CONFIRMED' },
        { week: 6, value: 2, label: '지켜보면 됨', source: 'CONFIRMED' },
      ],
    },
    {
      axis: 'HAND', axisLabel: '마비 쪽 손',
      values: [
        { week: 4, value: 0, label: '안 씀', source: 'CONFIRMED' },
        { week: 5, value: 0, label: '안 씀', source: 'CONFIRMED' },
        { week: 6, value: 1, label: '거들기만', source: 'CONFIRMED' },
      ],
    },
  ],
};

// 한 주는 값이 아예 없는 항목 — `p?.label ?? '·'` 자리표시 분기를 잡는다.
// summary의 변화 있는 항목(화장실 이용)은 세 주 모두 값을 갖고 있어 이 분기를
// 타지 않는다.
const missingWeekItem: Trajectory = {
  code: 'grooming', label: '세수·양치', changed: true,
  axes: [{
    axis: 'LEVEL', axisLabel: '도움 수준',
    values: [
      { week: 4, value: 2, label: '지켜보면 됨', source: 'CONFIRMED' },
      // 5주차 기록 없음
      { week: 6, value: 3, label: '혼자 하심', source: 'CONFIRMED' },
    ],
  }],
};

function renderIt(data: TherapistSummary | null = summary, status = 200) {
  server.use(http.get(`${BASE}/t/abc`, () =>
    data ? HttpResponse.json(data)
         : HttpResponse.json({ code: 'NOT_FOUND', message: '링크를 찾을 수 없습니다' }, { status })));
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/t/abc']}>
        <Routes><Route path="/t/:token" element={<Therapist />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('치료사용 요약', () => {
  it('궤적·신호·수면이 자유 기록보다 먼저 온다', async () => {
    renderIt();
    await screen.findByText('화장실 이용');

    const text = document.body.textContent ?? '';
    expect(text.indexOf('화장실 이용')).toBeLessThan(text.indexOf('찡그림'));
    expect(text.indexOf('찡그림')).toBeLessThan(text.indexOf('가끔 깨심'));
    expect(text.indexOf('가끔 깨심')).toBeLessThan(text.indexOf('아침에 혼자 화장실 다녀오셨어요.'));
  });

  it('수면을 주차별로 낸다', async () => {
    renderIt();
    expect(await screen.findByText('야간 수면')).toBeInTheDocument();
    expect(screen.getByText('잘 주무심')).toBeInTheDocument();
  });

  it('자유 기록을 원문 그대로, 시간대와 함께 낸다', async () => {
    renderIt();
    expect(await screen.findByText('아침에 혼자 화장실 다녀오셨어요.')).toBeInTheDocument();
    // /아침/만으로는 자유 기록 원문("아침에 혼자...")도 함께 걸려 두 개가 잡힌다 —
    // 시간대 표시 문단("6주 · 아침")을 정확히 지정한다.
    expect(screen.getByText('6주 · 아침')).toBeInTheDocument();
  });

  it('이어간 값은 옅게 표시하되 숨기거나 다른 색조를 쓰지 않는다', async () => {
    renderIt();
    await screen.findByText('화장실 이용');
    // 5주차 '지켜보면 됨'은 source: 'CARRIED'(보호자가 '달라진 것 없음'으로 이어간 값)이고,
    // 4주차 '손 잡아드림'은 source: 'CONFIRMED'다. 둘 다 화면에 그대로 보이되(숨기지 않는다),
    // CARRIED만 옅은 톤 클래스를 받고 색상 자체(의미색)는 바뀌지 않는다.
    const carried = screen.getByText('지켜보면 됨');
    const confirmed = screen.getByText('손 잡아드림');
    expect(carried.className).toContain('text-ink-faint');
    expect(confirmed.className).not.toContain('text-ink-faint');
  });

  it('두 축을 가진 항목은 축마다 한 줄로 나뉜다', async () => {
    renderIt({ ...summary, items: [...summary.items, twoAxisItem] });
    await screen.findByText('화장실 이용');

    // 항목 라벨과 축 라벨은 서로 다른 <span> 형제라 getByText로 합쳐서 찾을 수
    // 없다(둘 다 텍스트 노드를 직접 자식으로 갖는 별개 엘리먼트다) — 라벨로 행을
    // 찾은 뒤 그 칸의 textContent(자손까지 이어붙인 값)로 확인한다.
    // 첫 축(ai===0) 행: 항목 라벨 + ' · ' 연결어 + 첫 축 라벨이 한 칸에 담긴다.
    const firstRow = screen.getByText('옷 입기').closest('tr');
    expect(firstRow?.querySelector('td')?.textContent).toBe('옷 입기 · 도움 수준');
    // 두 번째 축(ai>0) 행: 축 라벨만 홀로 한 칸을 이룬다 — 라벨을 되풀이하지 않는다.
    const secondRow = screen.getByText('마비 쪽 손').closest('tr');
    expect(secondRow?.querySelector('td')?.textContent).toBe('마비 쪽 손');
    expect(secondRow).not.toBe(firstRow);
    // 두 줄 다 같은 항목의 주차별 값을 낸다.
    expect(screen.getByText('거들기만')).toBeInTheDocument();
  });

  it('그 주에 기록이 없으면 자리표시 점을 낸다', async () => {
    renderIt({ ...summary, items: [...summary.items, missingWeekItem] });
    await screen.findByText('화장실 이용');

    // 위와 같은 이유로 항목 라벨('세수·양치')만으로 행을 찾는다.
    const row = screen.getByText('세수·양치').closest('tr');
    const cells = row?.querySelectorAll('td');
    // cells[0]은 라벨 칸, [1]~[3]은 4주·5주·6주 값 칸이다.
    expect(cells?.[1]?.textContent).toBe('지켜보면 됨');
    expect(cells?.[2]?.textContent).toBe('·');
    expect(cells?.[3]?.textContent).toBe('혼자 하심');
  });

  it('기록 밀도와 작성자 변경을 보여준다', async () => {
    renderIt();
    expect(await screen.findByText(/6주 중 5주 기록/)).toBeInTheDocument();
    expect(screen.getByText(/5주차부터 아들/)).toBeInTheDocument();
  });

  it('한계 문단을 그대로 낸다', async () => {
    renderIt();
    expect(await screen.findByText(summary.disclaimer)).toBeInTheDocument();
  });

  it('보호자용 껍데기를 쓰지 않는다', async () => {
    renderIt();
    await screen.findByText('화장실 이용');
    expect(screen.queryByRole('link', { name: '← 홈' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '설정' })).not.toBeInTheDocument();
  });

  it('신호가 꺼진 케이스는 신호 구역을 만들지 않는다', async () => {
    renderIt({ ...summary, signalsEnabled: false, signals: [] });
    await screen.findByText('화장실 이용');
    expect(screen.queryByText('비언어 신호')).not.toBeInTheDocument();
  });

  it('링크가 죽었으면 그렇다고 말한다', async () => {
    renderIt(null, 404);
    expect(await screen.findByText(/이 주소는 더 이상 열리지 않습니다/)).toBeInTheDocument();
  });

  it('통신 장애는 링크가 죽었다고 말하지 않는다', async () => {
    // 네트워크 자체가 끊긴 경우(HttpResponse.error()는 fetch를 거부시켜 ApiError가 아닌
    // 순수 통신 오류를 던진다) 404와 같은 문구를 보여주면, 한 번의 접속 실패를 치료사에게
    // "이 링크는 폐기됐다"고 알리는 셈이다. 치료사의 다음 행동(보호자에게 새 링크 요청)이
    // finding 3을 거쳐 실제로는 살아있던 링크를 죽인다 — 그래서 이 문구가 404 전용이어야
    // 한다.
    server.use(http.get(`${BASE}/t/abc`, () => HttpResponse.error()));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/t/abc']}>
          <Routes><Route path="/t/:token" element={<Therapist />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('연결이 되지 않습니다. 잠시 후 다시 열어주세요.')).toBeInTheDocument();
    expect(screen.queryByText(/이 주소는 더 이상 열리지 않습니다/)).not.toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  // 판정 문구 화이트리스트 — 도달 가능한 상태마다 하나(docs/superpowers/plans/
  // 2026-09-06-frontend.md의 "화면당이 아니라 상태당" 정책). 서버는 판정 필드를
  // 전혀 주지 않으므로(TherapistSummaryDto에 그런 필드가 없다) 판정 문구는
  // 프론트가 지어내야만 생길 수 있다. 그래서 "이 화면은 판정을 안 만든다"는
  // 금지어 목록이 아니라 렌더된 전체를 화이트리스트로 걸어서 확인한다 — 목록에
  // 없는 말이 한 글자라도 끼어들면 아래 assertion들이 걸린다.
  // ------------------------------------------------------------------

  it('판정 문구를 만들지 않는다 — 불러오는 중', async () => {
    server.use(http.get(`${BASE}/t/abc`, () => new Promise(() => {})));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/t/abc']}>
          <Routes><Route path="/t/:token" element={<Therapist />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const main = await screen.findByRole('main');
    expect(main.textContent).toBe('불러오는 중입니다…');
  });

  it('판정 문구를 만들지 않는다 — 링크가 죽은 상태', async () => {
    renderIt(null, 404);
    const main = await screen.findByRole('main');
    await screen.findByText(/이 주소는 더 이상 열리지 않습니다/);
    expect(main.textContent).toBe(
      '이 주소는 더 이상 열리지 않습니다. 보호자분께 새 주소를 받아주세요.',
    );
  });

  it('판정 문구를 만들지 않는다 — 통신 장애 상태', async () => {
    server.use(http.get(`${BASE}/t/abc`, () => HttpResponse.error()));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/t/abc']}>
          <Routes><Route path="/t/:token" element={<Therapist />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const main = await screen.findByRole('main');
    await screen.findByText('연결이 되지 않습니다. 잠시 후 다시 열어주세요.');
    // 404 상태와 다른 문구여야 한다 — 이 화이트리스트가 걸리면 둘이 같은 문구를 쓰기
    // 시작했다는 뜻이다.
    expect(main.textContent).toBe('연결이 되지 않습니다. 잠시 후 다시 열어주세요.');
  });

  it('판정 문구를 만들지 않는다 — 채워진 상태', async () => {
    renderIt();
    const main = await screen.findByRole('main');
    await screen.findByText('화장실 이용');

    const toilet = summary.items[0]!;
    const bathing = summary.items[1]!;
    const toiletAxis = toilet.axes[0]!;
    const signal = summary.signals[0]!;
    const note = summary.freeNotes[0]!;
    const change = summary.authorChanges[0]!;

    // 표본 값에서 조립한다 — 통째로 다시 타이핑하면 오타로 스스로 속을 수 있다.
    // 연결어(' · ', ' — ', 가운뎃점, 이음줄)만 리터럴로 둔다.
    const expected = [
      '가정 관찰 기록',
      `${summary.weeks[0]}주차 ~ ${summary.weeks.at(-1)}주차 · ${summary.generatedAt.slice(0, 10)} 생성`,
      '주차별 관찰',
      '항목', ...summary.weeks.map((w) => `${w}주`),
      `${toilet.label} · ${toiletAxis.axisLabel}`,
      ...toiletAxis.values.map((p) => p.label),
      `같은 기간 변화 없음 — ${bathing.label}`,
      '옅은 값은 보호자가 ‘달라진 것 없음’으로 이어간 주입니다.',
      '비언어 신호',
      `${signal.actionLabel} · ${signal.kindLabel}`,
      summary.weeks.map((w) => (signal.weeks.includes(w) ? '●' : '·')).join(' '),
      '야간 수면',
      ...summary.sleep.flatMap((s) => [`${s.week}주`, s.label]),
      '보호자 기록 (원문)',
      `${note.week}주 · ${note.timeTagLabel}`,
      note.text,
      '보호자가 여쭤보고 싶은 것',
      ...summary.questions,
      ...summary.extraQuestions,
      '기록 밀도',
      `${summary.density.totalWeeks}주 중 ${summary.density.recordedWeeks}주 기록 · 그중 ${summary.density.confirmedWeeks}주는 직접 확인한 값`,
      `작성자 — ${summary.density.authors.join(', ')}`,
      `${change.week}주차부터 ${change.to}이(가) 작성 (이전 ${change.from})`,
      summary.disclaimer,
    ].join('');
    expect(main.textContent).toBe(expected);
  });

  it('판정 문구를 만들지 않는다 — 신호가 꺼진 상태', async () => {
    renderIt({ ...summary, signalsEnabled: false, signals: [] });
    const main = await screen.findByRole('main');
    await screen.findByText('화장실 이용');

    const toilet = summary.items[0]!;
    const bathing = summary.items[1]!;
    const toiletAxis = toilet.axes[0]!;
    const note = summary.freeNotes[0]!;
    const change = summary.authorChanges[0]!;

    // 위 '채워진 상태'와 같되 비언어 신호 구역 전체가 빠진다 — signalsEnabled가
    // false면 그 구역은 아예 렌더되지 않는다(빈 목록이 아니라 구역 자체가 없다).
    const expected = [
      '가정 관찰 기록',
      `${summary.weeks[0]}주차 ~ ${summary.weeks.at(-1)}주차 · ${summary.generatedAt.slice(0, 10)} 생성`,
      '주차별 관찰',
      '항목', ...summary.weeks.map((w) => `${w}주`),
      `${toilet.label} · ${toiletAxis.axisLabel}`,
      ...toiletAxis.values.map((p) => p.label),
      `같은 기간 변화 없음 — ${bathing.label}`,
      '옅은 값은 보호자가 ‘달라진 것 없음’으로 이어간 주입니다.',
      '야간 수면',
      ...summary.sleep.flatMap((s) => [`${s.week}주`, s.label]),
      '보호자 기록 (원문)',
      `${note.week}주 · ${note.timeTagLabel}`,
      note.text,
      '보호자가 여쭤보고 싶은 것',
      ...summary.questions,
      ...summary.extraQuestions,
      '기록 밀도',
      `${summary.density.totalWeeks}주 중 ${summary.density.recordedWeeks}주 기록 · 그중 ${summary.density.confirmedWeeks}주는 직접 확인한 값`,
      `작성자 — ${summary.density.authors.join(', ')}`,
      `${change.week}주차부터 ${change.to}이(가) 작성 (이전 ${change.from})`,
      summary.disclaimer,
    ].join('');
    expect(main.textContent).toBe(expected);
  });

  it('판정 문구를 만들지 않는다 — 신호는 켜져 있지만 관찰된 것이 없는 상태', async () => {
    renderIt({ ...summary, signals: [] });
    const main = await screen.findByRole('main');
    await screen.findByText('화장실 이용');

    const toilet = summary.items[0]!;
    const bathing = summary.items[1]!;
    const toiletAxis = toilet.axes[0]!;
    const note = summary.freeNotes[0]!;
    const change = summary.authorChanges[0]!;

    // signalsEnabled는 true인 채로 signals만 빈 배열이다 — 위 '신호가 꺼진 상태'와는
    // 다른 세 번째 상태다. 그쪽은 구역 자체가 없고, 여기는 구역은 남되 안에 고정
    // 문구 '관찰된 신호 없음'만 뜬다. 이 상태를 만드는 표본이 이 파일에 하나도
    // 없었다 — 하드코딩된 리터럴이라 안전해 보이지만, 이 프로젝트에서 판정
    // 문구가 숨었던 세 번의 재발급 모두 정확히 이런 조건부 분기의 리터럴
    // 자리였다. 아래에서 이 자리에 조작 문구 두 개를 심어 증명한다.
    const expected = [
      '가정 관찰 기록',
      `${summary.weeks[0]}주차 ~ ${summary.weeks.at(-1)}주차 · ${summary.generatedAt.slice(0, 10)} 생성`,
      '주차별 관찰',
      '항목', ...summary.weeks.map((w) => `${w}주`),
      `${toilet.label} · ${toiletAxis.axisLabel}`,
      ...toiletAxis.values.map((p) => p.label),
      `같은 기간 변화 없음 — ${bathing.label}`,
      '옅은 값은 보호자가 ‘달라진 것 없음’으로 이어간 주입니다.',
      '비언어 신호',
      '관찰된 신호 없음',
      '야간 수면',
      ...summary.sleep.flatMap((s) => [`${s.week}주`, s.label]),
      '보호자 기록 (원문)',
      `${note.week}주 · ${note.timeTagLabel}`,
      note.text,
      '보호자가 여쭤보고 싶은 것',
      ...summary.questions,
      ...summary.extraQuestions,
      '기록 밀도',
      `${summary.density.totalWeeks}주 중 ${summary.density.recordedWeeks}주 기록 · 그중 ${summary.density.confirmedWeeks}주는 직접 확인한 값`,
      `작성자 — ${summary.density.authors.join(', ')}`,
      `${change.week}주차부터 ${change.to}이(가) 작성 (이전 ${change.from})`,
      summary.disclaimer,
    ].join('');
    expect(main.textContent).toBe(expected);
  });

  it('판정 문구를 만들지 않는다 — 자유 기록이 없는 상태', async () => {
    renderIt({ ...summary, freeNotes: [] });
    const main = await screen.findByRole('main');
    await screen.findByText('화장실 이용');

    const toilet = summary.items[0]!;
    const bathing = summary.items[1]!;
    const toiletAxis = toilet.axes[0]!;
    const signal = summary.signals[0]!;
    const change = summary.authorChanges[0]!;

    // 위 '채워진 상태'와 같되 자유 기록 구역 전체가 빠진다 — freeNotes가 빈
    // 배열이면 '보호자 기록 (원문)' 구역 자체가 렌더되지 않는다.
    const expected = [
      '가정 관찰 기록',
      `${summary.weeks[0]}주차 ~ ${summary.weeks.at(-1)}주차 · ${summary.generatedAt.slice(0, 10)} 생성`,
      '주차별 관찰',
      '항목', ...summary.weeks.map((w) => `${w}주`),
      `${toilet.label} · ${toiletAxis.axisLabel}`,
      ...toiletAxis.values.map((p) => p.label),
      `같은 기간 변화 없음 — ${bathing.label}`,
      '옅은 값은 보호자가 ‘달라진 것 없음’으로 이어간 주입니다.',
      '비언어 신호',
      `${signal.actionLabel} · ${signal.kindLabel}`,
      summary.weeks.map((w) => (signal.weeks.includes(w) ? '●' : '·')).join(' '),
      '야간 수면',
      ...summary.sleep.flatMap((s) => [`${s.week}주`, s.label]),
      '보호자가 여쭤보고 싶은 것',
      ...summary.questions,
      ...summary.extraQuestions,
      '기록 밀도',
      `${summary.density.totalWeeks}주 중 ${summary.density.recordedWeeks}주 기록 · 그중 ${summary.density.confirmedWeeks}주는 직접 확인한 값`,
      `작성자 — ${summary.density.authors.join(', ')}`,
      `${change.week}주차부터 ${change.to}이(가) 작성 (이전 ${change.from})`,
      summary.disclaimer,
    ].join('');
    expect(main.textContent).toBe(expected);
  });

  it('판정 문구를 만들지 않는다 — 질문이 없는 상태', async () => {
    renderIt({ ...summary, questions: [], extraQuestions: [] });
    const main = await screen.findByRole('main');
    await screen.findByText('화장실 이용');

    const toilet = summary.items[0]!;
    const bathing = summary.items[1]!;
    const toiletAxis = toilet.axes[0]!;
    const signal = summary.signals[0]!;
    const note = summary.freeNotes[0]!;
    const change = summary.authorChanges[0]!;

    // 위 '채워진 상태'와 같되 질문 구역 전체가 빠진다 — questions와
    // extraQuestions가 모두 빈 배열이면 '보호자가 여쭤보고 싶은 것' 구역 자체가
    // 렌더되지 않는다.
    const expected = [
      '가정 관찰 기록',
      `${summary.weeks[0]}주차 ~ ${summary.weeks.at(-1)}주차 · ${summary.generatedAt.slice(0, 10)} 생성`,
      '주차별 관찰',
      '항목', ...summary.weeks.map((w) => `${w}주`),
      `${toilet.label} · ${toiletAxis.axisLabel}`,
      ...toiletAxis.values.map((p) => p.label),
      `같은 기간 변화 없음 — ${bathing.label}`,
      '옅은 값은 보호자가 ‘달라진 것 없음’으로 이어간 주입니다.',
      '비언어 신호',
      `${signal.actionLabel} · ${signal.kindLabel}`,
      summary.weeks.map((w) => (signal.weeks.includes(w) ? '●' : '·')).join(' '),
      '야간 수면',
      ...summary.sleep.flatMap((s) => [`${s.week}주`, s.label]),
      '보호자 기록 (원문)',
      `${note.week}주 · ${note.timeTagLabel}`,
      note.text,
      '기록 밀도',
      `${summary.density.totalWeeks}주 중 ${summary.density.recordedWeeks}주 기록 · 그중 ${summary.density.confirmedWeeks}주는 직접 확인한 값`,
      `작성자 — ${summary.density.authors.join(', ')}`,
      `${change.week}주차부터 ${change.to}이(가) 작성 (이전 ${change.from})`,
      summary.disclaimer,
    ].join('');
    expect(main.textContent).toBe(expected);
  });
});
