import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { act } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Record } from './Record';
import { catalogFixture, me } from '../test/fixtures';
import { setToken } from '../lib/api';
import { weeklyDraftKey } from '../lib/draft';
import type { Me, Trajectory } from '../lib/types';

const BASE = 'http://localhost:8080';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderRecord(m: Me, traj: Trajectory[] = []) {
  setToken('t');
  server.use(
    http.get(`${BASE}/me`, () => HttpResponse.json(m)),
    http.get(`${BASE}/me/trajectory`, () => HttpResponse.json(traj)),
  );
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Record catalog={catalogFixture} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('주간 기록', () => {
  it('연결 실패를 무한 로딩으로 숨기지 않고 다시 불러온다', async () => {
    setToken('t');
    server.use(http.get(`${BASE}/me`, () => new HttpResponse(null, { status: 503 })));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={qc}><MemoryRouter><Record catalog={catalogFixture} /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('기록 정보를 불러오지 못했습니다.');
    server.use(http.get(`${BASE}/me`, () => HttpResponse.json(me())));
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도하기' }));
    expect(await screen.findByRole('heading', { name: '지난주와 달라진 게 있나요?' })).toBeInTheDocument();
  });
  it('달라진 게 있는지부터 묻는다', async () => {
    renderRecord(me());
    expect(await screen.findByText('지난주와 달라진 게 있나요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '네, 달라진 게 있어요' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '없어요' })).toBeInTheDocument();
  });

  it('"없어요"를 눌러도 수면을 묻는다', async () => {
    const user = userEvent.setup();
    renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));

    expect(screen.getByText('밤에 어떻게 주무셨나요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '잘 주무심' })).toBeInTheDocument();
  });

  it('신호가 켜진 케이스는 "없었어요"를 큰 버튼으로 먼저 낸다', async () => {
    const user = userEvent.setup();
    renderRecord(me({ signalsEnabled: true }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));

    expect(screen.getByRole('button', { name: '없었어요' })).toBeInTheDocument();
  });

  it('전체 재확인 주는 미리 알리고 8항목을 순서대로 낸다', async () => {
    const user = userEvent.setup();
    renderRecord(me({ fullRecheck: true, week: 8 }));

    expect(await screen.findByText('이번 주는 8가지를 모두 여쭤봅니다')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '시작' }));
    expect(screen.getByText('침대·의자에서 옮겨 앉기')).toBeInTheDocument();
  });

  it('재확인 안내의 개수를 카탈로그에서 끌어온다', async () => {
    // 표본의 항목 수가 마침 8이라 상수로 박아도 오늘은 통과한다. 그것을 못 하게 고정한다.
    const short = { ...catalogFixture, items: catalogFixture.items.slice(0, 5) };
    setToken('t');
    server.use(
      http.get(`${BASE}/me`, () => HttpResponse.json(me({ fullRecheck: true, week: 8 }))),
      http.get(`${BASE}/me/trajectory`, () => HttpResponse.json([])),
    );
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter><Record catalog={short} /></MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('이번 주는 5가지를 모두 여쭤봅니다')).toBeInTheDocument();
  });

  it('전체 재확인 주에 지난달 답을 함께 보여준다', async () => {
    const user = userEvent.setup();
    renderRecord(me({ fullRecheck: true, week: 8 }), [{
      code: 'transfer', label: '침대·의자에서 옮겨 앉기', changed: true,
      axes: [{
        axis: 'LEVEL', axisLabel: '도움 수준',
        values: [{ week: 4, value: 1, label: '손 잡아드림', source: 'CONFIRMED' }],
      }],
    }]);

    await user.click(await screen.findByRole('button', { name: '시작' }));
    expect(screen.getByText('지난번에는 손 잡아드림')).toBeInTheDocument();
  });

  it('자유 기록 칸이 처음부터 여러 줄이다', async () => {
    const user = userEvent.setup();
    renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    // 수면 선택은 화면을 넘기지 않는다. 마음을 바꿀 수 있어야 하므로 '다음'을 눌러야 간다.
    await user.click(screen.getByRole('button', { name: '다음' }));

    const box = screen.getByLabelText('말씀하시듯 편하게 적어주세요');
    expect(box.tagName).toBe('TEXTAREA');
    expect(Number(box.getAttribute('rows'))).toBeGreaterThanOrEqual(4);
  });

  it('저장하면 서버로 보내고 홈으로 돌아간다', async () => {
    const user = userEvent.setup();
    let sent: any = null;
    server.use(http.put(`${BASE}/me/weeks/6`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ week: 6, kind: 'WEEKLY', questionsRefreshed: true });
    }));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '가끔 깨심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    await screen.findByText('기록을 남겼습니다.');
    expect(sent.noChange).toBe(true);
    expect(sent.sleep).toBe(1);
    expect(sent.painSignal).toBeNull();
    expect(sent.freeNote).toBeNull();
  });

  it('자유 기록과 시간대를 원문 그대로 서버에 보낸다', async () => {
    // README §5: "원문을 반드시 그대로 보존하세요." freeNote/timeTag에 대한 지금까지의
    // assertion은 전부 toBeNull()뿐이었다 — 여기서 입력한 글자가 실제로 요청 본문에
    // 실려 가는지는 아무 테스트도 보지 않았다. 20자보다 길게 적어, trim().slice(0, 20)
    // 같은 자르기 변이가 섞여도 이 assertion이 걸리게 한다.
    const user = userEvent.setup();
    let sent: any = null;
    server.use(http.put(`${BASE}/me/weeks/6`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ week: 6, kind: 'WEEKLY', questionsRefreshed: true });
    }));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));

    const note = '오늘 오후에는 유난히 오른쪽 어깨를 자주 만지시고 표정이 좋지 않으셨습니다';
    expect(note.length).toBeGreaterThan(20);
    await user.type(screen.getByLabelText('말씀하시듯 편하게 적어주세요'), note);
    await user.click(screen.getByRole('button', { name: '오후' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    await screen.findByText('기록을 남겼습니다.');
    expect(sent.freeNote.text).toBe(note);
    expect(sent.freeNote.timeTag).toBe('AFTERNOON');
  });

  it('주차가 넘어가면 말없이 재시도하지 않고 물어본다', async () => {
    const user = userEvent.setup();
    server.use(http.put(`${BASE}/me/weeks/6`, () =>
      HttpResponse.json({ code: 'WEEK_MISMATCH', message: '이번 주는 7주차입니다' }, { status: 409 })));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    // 지난주 관찰이 이번 주 기록이 되면 안 된다. 층 1은 버리지도 옮기지도 않는다.
    expect(await screen.findByText(/날짜가 바뀌었습니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이번 주 것입니다' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '지난주 것입니다' })).toBeInTheDocument();
  });

  it('저장에 실패하면 서버 문구를 그대로 보여준다', async () => {
    const user = userEvent.setup();
    server.use(http.put(`${BASE}/me/weeks/6`, () =>
      HttpResponse.json({ code: 'VALIDATION', message: 'sleep은 0..2입니다' }, { status: 400 })));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    expect(await screen.findByText('sleep은 0..2입니다')).toBeInTheDocument();
  });

  // ------------------------------------------------------------------
  // 판정 문구 화이트리스트 — 도달 가능한 단계 종류마다 하나(Home.test.tsx·Settings.test.tsx와
  // 같은 정책). 이 화면은 764줄(Record+Onboarding) 중 하나로 화이트리스트가 전혀 없었다.
  // 금지어 나열이 아니라 렌더된 전체를 화이트리스트로 건다 — 목록에 없는 말이 한 글자라도
  // 끼어들면 아래 assertion들이 걸린다. Screen에는 landmark 롤이 없어(<main>이 아니다)
  // container.textContent로 전체를 본다.
  // ------------------------------------------------------------------

  it('판정 문구를 만들지 않는다 — ask 단계', async () => {
    const { container } = renderRecord(me());
    await screen.findByText('지난주와 달라진 게 있나요?');
    expect(container.textContent).toBe(
      ['이번 주 관찰', '1 / 3', '지난주와 달라진 게 있나요?', '네, 달라진 게 있어요', '없어요'].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — recheck-intro 단계', async () => {
    const { container } = renderRecord(me({ fullRecheck: true, week: 8 }));
    await screen.findByText('이번 주는 8가지를 모두 여쭤봅니다');
    expect(container.textContent).toBe(
      [
        '이번 주 관찰', '1 / 11',
        '이번 주는 8가지를 모두 여쭤봅니다',
        '네 주에 한 번, 놓친 것이 없는지 처음부터 확인합니다. 지난번 답도 함께 보여드립니다.',
        '시작',
      ].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — pick 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '네, 달라진 게 있어요' }));
    await screen.findByText('어떤 것이 달라졌나요?');
    expect(container.textContent).toBe(
      [
        '← 뒤로', '이번 주 관찰', '2 / 4',
        '어떤 것이 달라졌나요?',
        '여러 개를 고르셔도 됩니다.',
        ...catalogFixture.items.map((i) => i.label),
        '다음',
      ].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — item 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '네, 달라진 게 있어요' }));
    await user.click(await screen.findByRole('button', { name: '화장실 이용' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await screen.findByText('요즘 어떠신가요?');

    // toilet(화장실 이용)은 LEVEL·CONSISTENCY 두 축이다(catalogFixture). LEVEL은 축
    // 이름 h3을 안 낸다(단일 축일 때 반복해 말하지 않는다는 Record.tsx의 규칙).
    expect(container.textContent).toBe(
      [
        '← 뒤로', '이번 주 관찰', '3 / 5',
        '화장실 이용',
        '요즘 어떠신가요?',
        ...catalogFixture.axes.LEVEL!.map((v) => v.label),
        '이번 주에 얼마나 자주 그러셨나요?',
        ...catalogFixture.axes.CONSISTENCY!.map((v) => v.label),
        '한 줄 적어두실 것이 있나요? (안 적으셔도 됩니다)',
        '다음',
      ].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — signal 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderRecord(me({ signalsEnabled: true }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await screen.findByText('이번 주에 불편해 보이신 적이 있나요?');

    expect(container.textContent).toBe(
      [
        '← 뒤로', '이번 주 관찰', '2 / 4',
        '이번 주에 불편해 보이신 적이 있나요?',
        '말씀으로 표현이 어려우실 때, 표정이나 몸짓에서 보이는 것들입니다.',
        '없었어요',
        ...catalogFixture.signalActions.flatMap((a) => [
          a.label,
          ...catalogFixture.signalKinds.map((k) => k.label),
        ]),
        '다음',
      ].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — sleep 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await screen.findByText('밤에 어떻게 주무셨나요?');

    expect(container.textContent).toBe(
      [
        '← 뒤로', '이번 주 관찰', '2 / 3',
        '밤에 어떻게 주무셨나요?',
        '이번 주 대체로 어떠셨는지로 골라주세요.',
        ...catalogFixture.sleepLevels.map((l) => l.label),
        '다음',
      ].join(''),
    );
  });

  it('판정 문구를 만들지 않는다 — note 단계', async () => {
    const user = userEvent.setup();
    const { container } = renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(await screen.findByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await screen.findByText('그 밖에 남기고 싶으신 것');

    expect(container.textContent).toBe(
      [
        '← 뒤로', '이번 주 관찰', '3 / 3',
        '그 밖에 남기고 싶으신 것',
        '말씀하시듯 편하게 적어주세요',
        '키보드의 마이크를 누르면 말로 적을 수 있어요.',
        '주로 언제였나요? (안 고르셔도 됩니다)',
        ...catalogFixture.timeTags.map((t) => t.label),
        '저장하기',
      ].join(''),
    );
  });
});

describe('중복 제출 방지', () => {
  it('저장하기를 빠르게 두 번 눌러도 요청은 한 번만 나간다', async () => {
    const user = userEvent.setup();
    let calls = 0;
    server.use(http.put(`${BASE}/me/weeks/6`, async () => {
      calls += 1;
      return HttpResponse.json({ week: 6, kind: 'WEEKLY', questionsRefreshed: true });
    }));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));

    const saveBtn = screen.getByRole('button', { name: '저장하기' });
    // userEvent.click은 클릭마다 act()로 감싸 마이크로태스크를 흘려보낸다 — 두 번을 await로
    // 이어 부르면 "빠른 두 번"이 아니라 사실상의 순차 클릭이 된다. 같은 틱 안의 두 번을
    // 재현하려면 fireEvent로 사이에 아무것도 기다리지 않고 연달아 누른다.
    //
    // 두 fireEvent를 하나의 바깥 act()로 묶는다. fireEvent 자신도 내부에서 act()를 걸지만,
    // React는 중첩된 act 스코프의 커밋을 가장 바깥 act가 끝날 때까지 미룬다 — 그래서 첫
    // 클릭의 setError(null)이 부르는 렌더(disabled를 true로 반영)가 두 번째 fireEvent보다
    // 먼저 끼어들지 못한다. 이게 바로 sending ref 주석이 말하는 "그 사이에 렌더가 없는"
    // 경우의 재현이다 — 이 바깥 act() 없이 두 fireEvent를 그냥 연달아 부르면, 각각이 제
    // act 스코프를 갖고 끝나면서 첫 클릭이 disabled를 이미 올려버려 두 번째 fireEvent는
    // DOM 차원(비활성 버튼)에서 걸러진다. 그러면 sending.current 검사는 한 번도 실행되지
    // 않고, 이 잠금을 지워도 이 테스트는 여전히 통과한다 — 이 테스트가 실제로 막고 싶은
    // 상황(같은 렌더 사이클 안의 두 번째 이벤트)을 하나도 확인하지 못한 채로.
    act(() => {
      fireEvent.click(saveBtn);
      fireEvent.click(saveBtn);
    });

    await screen.findByText('기록을 남겼습니다.');
    expect(calls).toBe(1);
  });

  it('저장에 실패한 뒤 다시 누르면 성공한다', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    server.use(http.put(`${BASE}/me/weeks/6`, () => {
      attempt += 1;
      if (attempt === 1) {
        return HttpResponse.json({ code: 'VALIDATION', message: 'sleep은 0..2입니다' }, { status: 400 });
      }
      return HttpResponse.json({ week: 6, kind: 'WEEKLY', questionsRefreshed: true });
    }));

    renderRecord(me({ week: 6 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    expect(await screen.findByText('sleep은 0..2입니다')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '저장하기' }));

    await screen.findByText('기록을 남겼습니다.');
    expect(attempt).toBe(2);
  });
});

/** 현재 라우터 위치를 지켜본다. Record는 Routes 없이 렌더되므로 navigate()가 실제로
 * 어디로 갔는지는 이렇게 형제로 심어야 확인할 수 있다(OnboardingBaseline.test.tsx와 같다). */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

describe('주차 불일치 해소', () => {
  it('/me를 다시 받는 동안에는 두 버튼이 잠긴다', async () => {
    const user = userEvent.setup();
    let call = 0;
    // 일반 let 변수로 두면, 클로저 안에서만 대입한다는 이유로 TS가 사용 지점에서
    // null로 좁혀버린다(할당이 실제로 언제 실행될지는 흐름 분석이 알 수 없는데도).
    // 객체 프로퍼티로 감싸면 그 좁히기를 피한다.
    const refetchGate: { resolve: (() => void) | null } = { resolve: null };
    server.use(
      http.get(`${BASE}/me`, async () => {
        call += 1;
        if (call > 1) {
          await new Promise<void>((resolve) => { refetchGate.resolve = resolve; });
        }
        return HttpResponse.json(me({ week: 6 }));
      }),
      http.get(`${BASE}/me/trajectory`, () => HttpResponse.json([])),
      http.put(`${BASE}/me/weeks/6`, () =>
        HttpResponse.json({ code: 'WEEK_MISMATCH', message: '이번 주는 7주차입니다' }, { status: 409 })),
    );
    setToken('t');
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter><Record catalog={catalogFixture} /></MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    const thisWeekBtn = await screen.findByRole('button', { name: '이번 주 것입니다' });
    const lastWeekBtn = screen.getByRole('button', { name: '지난주 것입니다' });
    await waitFor(() => expect(thisWeekBtn).toBeDisabled());
    expect(lastWeekBtn).toBeDisabled();

    // 잠긴 동안 눌러도 아무 일도 일어나지 않아야 한다 — 실제 클릭으로 확인한다.
    await user.click(thisWeekBtn);
    expect(screen.getByText(/날짜가 바뀌었습니다/)).toBeInTheDocument();

    refetchGate.resolve?.();
    await waitFor(() => expect(thisWeekBtn).not.toBeDisabled());
    expect(lastWeekBtn).not.toBeDisabled();
  });

  it('이번 주 것으로 답하면 새 주차로 흐름이 이어진다', async () => {
    const user = userEvent.setup();
    let call = 0;
    server.use(
      http.get(`${BASE}/me`, () => {
        call += 1;
        return HttpResponse.json(call === 1 ? me({ week: 6 }) : me({ week: 7 }));
      }),
      http.get(`${BASE}/me/trajectory`, () => HttpResponse.json([])),
      http.put(`${BASE}/me/weeks/6`, () =>
        HttpResponse.json({ code: 'WEEK_MISMATCH', message: '이번 주는 7주차입니다' }, { status: 409 })),
      http.put(`${BASE}/me/weeks/7`, () =>
        HttpResponse.json({ week: 7, kind: 'WEEKLY', questionsRefreshed: true })),
    );
    setToken('t');
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter><Record catalog={catalogFixture} /></MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    const resolveBtn = await screen.findByRole('button', { name: '이번 주 것입니다' });
    await waitFor(() => expect(resolveBtn).not.toBeDisabled());
    await user.click(resolveBtn);

    // 물음이 사라지고 마지막 단계(그 밖에 남기고 싶은 것)로 돌아온다 — 답은 그대로다.
    expect(await screen.findByText('그 밖에 남기고 싶으신 것')).toBeInTheDocument();
    expect(screen.queryByText(/날짜가 바뀌었습니다/)).not.toBeInTheDocument();

    // 새 주차로 실제 저장까지 이어지는지 끝까지 확인한다.
    await user.click(screen.getByRole('button', { name: '저장하기' }));
    await screen.findByText('기록을 남겼습니다.');
  });

  it('이번 주 것이 전체 재확인 주면 흐름을 처음부터 다시 연다', async () => {
    const user = userEvent.setup();
    let call = 0;
    server.use(
      http.get(`${BASE}/me`, () => {
        call += 1;
        return HttpResponse.json(call === 1 ? me({ week: 6 }) : me({ week: 8, fullRecheck: true }));
      }),
      http.get(`${BASE}/me/trajectory`, () => HttpResponse.json([])),
      http.put(`${BASE}/me/weeks/6`, () =>
        HttpResponse.json({ code: 'WEEK_MISMATCH', message: '이번 주는 8주차입니다' }, { status: 409 })),
    );
    setToken('t');
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter><Record catalog={catalogFixture} /></MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '저장하기' }));

    const resolveBtn = await screen.findByRole('button', { name: '이번 주 것입니다' });
    await waitFor(() => expect(resolveBtn).not.toBeDisabled());
    await user.click(resolveBtn);

    expect(await screen.findByText('이번 주는 8가지를 모두 여쭤봅니다')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '시작' }));
    // 이전에 남기던 부분 답(수면 등)이 아니라 8항목의 맨 처음부터 — 새로 연 흐름이라는 뜻이다.
    expect(screen.getByText('침대·의자에서 옮겨 앉기')).toBeInTheDocument();
  });

  it('지난주 것으로 답하면 초안을 지우고 홈으로 보낸다', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(`${BASE}/me`, () => HttpResponse.json(me({ week: 6 }))),
      http.get(`${BASE}/me/trajectory`, () => HttpResponse.json([])),
      http.put(`${BASE}/me/weeks/6`, () =>
        HttpResponse.json({ code: 'WEEK_MISMATCH', message: '이번 주는 7주차입니다' }, { status: 409 })),
    );
    setToken('t');
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <Record catalog={catalogFixture} />
          <LocationProbe />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));

    const draftKey = weeklyDraftKey(me().caseId, me().week);
    expect(localStorage.getItem(draftKey)).not.toBeNull(); // 여기까지 오는 사이 초안이 이미 쌓였다

    await user.click(screen.getByRole('button', { name: '저장하기' }));

    const lastWeekBtn = await screen.findByRole('button', { name: '지난주 것입니다' });
    await waitFor(() => expect(lastWeekBtn).not.toBeDisabled());
    await user.click(lastWeekBtn);

    expect(await screen.findByTestId('location')).toHaveTextContent(/^\/$/);
    expect(localStorage.getItem(draftKey)).toBeNull();
  });
});

describe('마이크 안내', () => {
  it('첫 방문에서는 보이고, 입력하는 중에도 그 방문 동안은 사라지지 않는다', async () => {
    const user = userEvent.setup();
    renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));

    expect(await screen.findByText('키보드의 마이크를 누르면 말로 적을 수 있어요.')).toBeInTheDocument();

    // 안내가 뜬 방문 중에 글자를 입력해 재렌더시켜도 — 안내가 문장 중간에 사라지면 안 된다.
    await user.type(screen.getByLabelText('말씀하시듯 편하게 적어주세요'), '오늘은 괜찮으셨다');

    expect(screen.getByText('키보드의 마이크를 누르면 말로 적을 수 있어요.')).toBeInTheDocument();
  });

  it('한 번 본 뒤에는 다시 마운트해도 보이지 않는다', async () => {
    const user = userEvent.setup();
    const view = renderRecord(me());
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await screen.findByText('키보드의 마이크를 누르면 말로 적을 수 있어요.');

    view.unmount();
    // 다음 주(다른 draft 키)로 다시 마운트한다 — 이번 기록 초안(index 등)과 섞이지 않게
    // 하려는 것뿐이다. 마이크 안내 플래그(DICTATION_HINT_SEEN)는 주차·케이스와 무관한
    // 전역 키라 그대로 유지된다.
    renderRecord(me({ week: 7 }));
    await user.click(await screen.findByRole('button', { name: '없어요' }));
    await user.click(screen.getByRole('button', { name: '잘 주무심' }));
    await user.click(screen.getByRole('button', { name: '다음' }));

    expect(await screen.findByText('그 밖에 남기고 싶으신 것')).toBeInTheDocument();
    expect(screen.queryByText('키보드의 마이크를 누르면 말로 적을 수 있어요.')).not.toBeInTheDocument();
  });
});

describe('임시 저장 이어가기', () => {
  it('로컬에 남아있는 초안을 그대로 이어서 보여준다', async () => {
    const draftKey = weeklyDraftKey(me().caseId, me().week);
    localStorage.setItem(draftKey, JSON.stringify({
      index: 2,
      noChange: false,
      selected: ['toilet'],
      items: {},
      painSignal: null,
      sleep: null,
      freeNote: { text: '', timeTag: null },
    }));

    renderRecord(me());

    // '지난주와 달라진 게 있나요?' 처음 화면이 아니라, 초안이 가리키던 항목 화면으로 바로 간다.
    expect(await screen.findByText('화장실 이용')).toBeInTheDocument();
    expect(screen.getByText('요즘 어떠신가요?')).toBeInTheDocument();
    expect(screen.queryByText('지난주와 달라진 게 있나요?')).not.toBeInTheDocument();
  });
});
