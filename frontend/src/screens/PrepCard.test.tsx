import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { PrepCard } from './PrepCard';
import { setToken } from '../lib/api';
import type { PrepCard as Card } from '../lib/types';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const full: Card = {
  week: 6, nextVisitDate: '2026-09-08',
  questions: [{
    rank: 1, type: 'PLATEAU', source: 'engine',
    sentence: '집 안에서 걷기는 왜 안 늘고 있을까요?',
    evidence: {
      items: [{
        code: 'ambulation', label: '집 안에서 걷기', axis: 'LEVEL', axisLabel: '도움 수준',
        values: [{ week: 5, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
      }],
      signal: null,
    },
  }],
  extraQuestions: ['밤에 자주 깨시는데 괜찮은가요?'],
  emptyMessage: null,
  therapistGlance: ['화장실 이용 · 도움 수준 4주째 유지'],
};

// 질문도 추가 질문도 다음 진료일도 요약도 없는, 완전히 빈 상태. 고정 UI 문구
// 밖에 남는 게 없어야 화이트리스트의 기대 문자열이 작고 안정적으로 유지된다.
const empty: Card = {
  week: 6, nextVisitDate: null,
  questions: [],
  extraQuestions: [],
  emptyMessage: null,
  therapistGlance: [],
};

// 통증 신호 근거(evidence.signal) 줄은 다른 어떤 표본에도 없어 커버되지 않았다.
const withSignal: Card = {
  week: 6, nextVisitDate: '2026-09-08',
  questions: [{
    rank: 1, type: 'PLATEAU', source: 'engine',
    sentence: '집 안에서 걷기는 왜 안 늘고 있을까요?',
    evidence: {
      items: [{
        code: 'ambulation', label: '집 안에서 걷기', axis: 'LEVEL', axisLabel: '도움 수준',
        values: [{ week: 5, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
      }],
      signal: {
        action: 'STANDING', actionLabel: '일어설 때',
        kind: 'GRIMACE', kindLabel: '찡그림',
        weeks: [5, 6], window: 2,
      },
    },
  }],
  extraQuestions: [],
  emptyMessage: null,
  therapistGlance: [],
};

function renderIt(card: Card) {
  setToken('t');
  server.use(http.get(`${BASE}/me/prep-card`, () => HttpResponse.json(card)));
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><PrepCard /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('진료 준비 카드', () => {
  it('저장 성공 뒤 재조회가 실패해도 방금 저장한 질문을 유지한다', async () => {
    renderIt(full);
    const user = userEvent.setup();
    const input = await screen.findByLabelText('여쭤보고 싶은 것 1');
    await user.clear(input);
    await user.type(input, '새 질문');
    server.use(
      http.put(`${BASE}/me/prep-card/extra`, () => HttpResponse.json(['새 질문'])),
      http.get(`${BASE}/me/prep-card`, () => new HttpResponse(null, { status: 503 })),
    );
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByRole('status')).toHaveTextContent('질문을 저장했습니다.');
    expect(input).toHaveValue('새 질문');
  });
  it('저장 응답이 늦어도 그동안 새로 쓴 질문은 덮어쓰지 않는다', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    let questions = full.extraQuestions;
    server.use(http.put(`${BASE}/me/prep-card/extra`, async ({ request }) => {
      questions = (await request.json() as { questions: string[] }).questions;
      await gate;
      return HttpResponse.json(questions);
    }));
    renderIt(full);
    const user = userEvent.setup();
    const input = await screen.findByLabelText('여쭤보고 싶은 것 1');
    await user.clear(input);
    await user.type(input, '첫 질문');
    await user.click(screen.getByRole('button', { name: '저장' }));
    await user.clear(input);
    await user.type(input, '저장 중 새로 쓴 질문');
    let reads = 0;
    server.use(http.get(`${BASE}/me/prep-card`, () => {
      reads++;
      return HttpResponse.json({ ...full, extraQuestions: questions });
    }));
    release();
    await waitFor(() => expect(reads).toBeGreaterThan(0));
    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toBeEnabled());
    expect(input).toHaveValue('저장 중 새로 쓴 질문');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByRole('status')).toHaveTextContent('질문을 저장했습니다.');
    expect(questions).toEqual(['저장 중 새로 쓴 질문']);
  });
  it('질문 안에서 해당 근거를 펼친다', async () => {
    renderIt(full);
    const article = await screen.findByRole('article', { name: full.questions[0]!.sentence });
    await userEvent.setup().click(within(article).getByRole('button', { name: '이 질문의 관찰 근거' }));
    expect(within(article).getByText('손 잡아드림')).toBeInTheDocument();
  });

  it('저장 실패 후 입력을 보존하고 재시도 성공을 알린다', async () => {
    renderIt(full);
    const user = userEvent.setup();
    const input = await screen.findByLabelText('여쭤보고 싶은 것 1');
    await user.clear(input);
    await user.type(input, '걸을 때 어떤 도움을 드릴까요?');
    server.use(http.put(`${BASE}/me/prep-card/extra`, () => new HttpResponse(null, { status: 503 })));
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('입력한 내용은 그대로 있습니다.');
    expect(input).toHaveValue('걸을 때 어떤 도움을 드릴까요?');
    server.use(
      http.put(`${BASE}/me/prep-card/extra`, () => HttpResponse.json(['걸을 때 어떤 도움을 드릴까요?'])),
      http.get(`${BASE}/me/prep-card`, () => HttpResponse.json({ ...full, extraQuestions: ['걸을 때 어떤 도움을 드릴까요?'] })),
    );
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByRole('status')).toHaveTextContent('질문을 저장했습니다.');
    expect(input).toHaveValue('걸을 때 어떤 도움을 드릴까요?');
  });
  it('질문 문장을 서버 그대로 낸다', async () => {
    renderIt(full);
    expect(await screen.findByText('집 안에서 걷기는 왜 안 늘고 있을까요?')).toBeInTheDocument();
    expect(screen.getByText('9월 8일 진료')).toBeInTheDocument();
  });

  it('근거는 접혀 있다가 펴진다', async () => {
    const user = userEvent.setup();
    renderIt(full);
    await screen.findByText('집 안에서 걷기는 왜 안 늘고 있을까요?');

    expect(screen.queryByText('손 잡아드림')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '이 질문의 관찰 근거' }));
    expect(screen.getByText('손 잡아드림')).toBeInTheDocument();
  });

  it('질문이 없으면 빈 칸을 만들지 않고 서버 문구만 낸다', async () => {
    renderIt({ ...full, questions: [], emptyMessage: '이번에는 특별히 여쭤볼 것이 없습니다.' });
    expect(await screen.findByText('이번에는 특별히 여쭤볼 것이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '이 질문의 관찰 근거' })).not.toBeInTheDocument();
  });

  it('추가 질문을 더하고 저장한다', async () => {
    const user = userEvent.setup();
    let sent: any = null;
    renderIt(full);
    server.use(http.put(`${BASE}/me/prep-card/extra`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(['밤에 자주 깨시는데 괜찮은가요?', '약을 바꿔야 할까요?']);
    }));

    await screen.findByText('내가 더 여쭤보고 싶은 것');
    await user.click(screen.getByRole('button', { name: '+ 추가' }));
    // 칸마다 순번이 붙어 있어 방금 붙은 두 번째(빈) 칸을 이름으로 바로 집을 수 있다.
    await user.type(screen.getByLabelText('여쭤보고 싶은 것 2'), '약을 바꿔야 할까요?');
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(sent.questions).toEqual(['밤에 자주 깨시는데 괜찮은가요?', '약을 바꿔야 할까요?']);
  });

  it('추가 질문은 다섯 개까지다', async () => {
    renderIt({ ...full, extraQuestions: ['1', '2', '3', '4', '5'] });
    await screen.findByText('내가 더 여쭤보고 싶은 것');
    expect(screen.queryByRole('button', { name: '+ 추가' })).not.toBeInTheDocument();
  });

  it('진료실에서 보여드릴 요약과 링크 발급이 아래에 있다', async () => {
    renderIt(full);
    expect(await screen.findByText('진료실에서 보여드릴 요약')).toBeInTheDocument();
    expect(screen.getByText('화장실 이용 · 도움 수준 4주째 유지')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '치료사에게 보여드리기' })).toBeInTheDocument();
  });

  it('판정 문구를 만들지 않는다 — 빈 상태', async () => {
    renderIt(empty);
    await screen.findByText('내가 더 여쭤보고 싶은 것');
    const main = screen.getByRole('main');

    // 금지어 나열이 아니라 전체를 화이트리스트로 건다(Home.test.tsx·Trajectory.test.tsx와 같은
    // 방식). 질문도 추가 질문도 다음 진료일도 없는 빈 상태라 화면에는 고정 UI 문구만
    // 남아야 한다 — 서버가 보내지 않은 문장이 한 글자라도 끼어들면 이 assertion이 걸린다.
    //
    // 이 표본은 questions: []라 data.questions.map(...) 분기(서버가 보낸 질문 문장이
    // 실제로 찍히는 자리)를 아예 타지 않는다 — 판정 문구가 가장 위험한 자리를 비켜간다.
    // 질문이 있는 상태는 아래의 '질문이 있는 상태' 테스트가 별도로 건다.
    expect(main.textContent).toBe(
      '← 뒤로진료 준비내가 더 여쭤보고 싶은 것+ 추가치료사에게 보여드리기',
    );
  });

  it('판정 문구를 만들지 않는다 — 질문이 있는 상태', async () => {
    const user = userEvent.setup();
    renderIt(full);
    await screen.findByText('집 안에서 걷기는 왜 안 늘고 있을까요?');
    const main = screen.getByRole('main');

    const q = full.questions[0]!;
    const evItem = q.evidence.items[0]!;
    const evValue = evItem.values[0]!;
    const glance = full.therapistGlance[0]!;

    // 표본 값에서 기대 문자열을 조립한다 — 문구를 통째로 다시 타이핑하면 오타로
    // 스스로 속을 수 있다. '9월 8일 진료'만은 리터럴로 둔다: 위 '질문 문장을 서버
    // 그대로 낸다' 테스트가 이미 같은 리터럴로 이 표시를 고정하고 있다.
    //
    // 접힌 상태: data.questions.map(...) 분기가 실제로 실행된다 — 여기가 재검토가
    // 조작된 문구를 심어 통과시켰던 바로 그 자리다. extraQuestions의 실제 텍스트는
    // textarea의 내용과 접근성 라벨도 함께 검증한다.
    const collapsed = [
      '← 뒤로',
      '9월 8일 진료',
      `질문 ${q.rank}${q.sentence}`,
      '이 질문의 관찰 근거',
      '내가 더 여쭤보고 싶은 것',
      '여쭤보고 싶은 것 1', full.extraQuestions[0]!,
      '+ 추가',
      '진료실에서 보여드릴 요약',
      glance,
      '치료사에게 보여드리기',
    ].join('');
    expect(main.textContent).toBe(collapsed);

    // 펼친 상태: 근거(값들)도 실제 렌더 경로를 탄다 — 캐어기버가 진료실에서 실제로
    // 펼쳐 보일 상태이기도 하다. Collapse는 열리면 버튼 문구가 "라벨 접기"로 바뀐다.
    await user.click(screen.getByRole('button', { name: '이 질문의 관찰 근거' }));
    const expanded = [
      '← 뒤로',
      '9월 8일 진료',
      `질문 ${q.rank}${q.sentence}`,
      '이 질문의 관찰 근거 접기',
      `${evItem.label} · ${evItem.axisLabel}`,
      '좌우로 밀어 주차별 기록을 볼 수 있어요',
      `${evValue.week}주${evValue.label}직접 확인`,
      '내가 더 여쭤보고 싶은 것',
      '여쭤보고 싶은 것 1', full.extraQuestions[0]!,
      '+ 추가',
      '진료실에서 보여드릴 요약',
      glance,
      '치료사에게 보여드리기',
    ].join('');
    expect(main.textContent).toBe(expanded);
  });

  it('통증 신호 근거도 함께 보여준다', async () => {
    const user = userEvent.setup();
    renderIt(withSignal);
    await screen.findByText('집 안에서 걷기는 왜 안 늘고 있을까요?');

    expect(screen.queryByText('일어설 때 · 찡그림 — 5주, 6주')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '이 질문의 관찰 근거' }));
    expect(screen.getByText('일어설 때 · 찡그림 — 5주, 6주')).toBeInTheDocument();
  });
});
