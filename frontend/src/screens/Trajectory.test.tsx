import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Trajectory } from './Trajectory';
import { setToken } from '../lib/api';
import type { Trajectory as T } from '../lib/types';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const data: T[] = [
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
    // value/label은 토일렛의 4~6주(손 잡아드림·지켜보면 됨·혼자 하심)와 겹치지 않는
    // 라벨을 쓴다 — 겹치면 접힌 목욕 값이 이미 화면에 있는 토일렛 값과 같아 보여서
    // '접었다가 펴야 보인다'를 증명하지 못한다.
    code: 'bathing', label: '목욕', changed: false,
    axes: [{
      axis: 'LEVEL', axisLabel: '도움 수준',
      values: [{ week: 6, value: 0, label: '대부분 도움', source: 'CONFIRMED' }],
    }],
  },
];

function renderIt() {
  setToken('t');
  server.use(http.get(`${BASE}/me/trajectory`, () => HttpResponse.json(data)));
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Trajectory /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('전체 궤적', () => {
  it('주차별 값을 라벨로 보여준다', async () => {
    renderIt();
    expect(await screen.findByText('화장실 이용')).toBeInTheDocument();
    expect(screen.getByText('혼자 하심')).toBeInTheDocument();
  });

  it('바뀐 것이 없는 항목은 접어둔다', async () => {
    const user = userEvent.setup();
    renderIt();
    await screen.findByText('화장실 이용');

    expect(screen.queryByText('대부분 도움')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /목욕/ }));
    expect(screen.getByText('대부분 도움')).toBeInTheDocument();
  });

  it('이어진 주를 글자와 범례로 알린다', async () => {
    renderIt();
    await screen.findByText('화장실 이용');
    // 컴포넌트는 &lsquo;/&rsquo;로 곡선 따옴표를 렌더한다 — 직선 따옴표(')로 적으면
    // 실제 DOM 문자와 달라 절대 매치되지 않는다. ‘/’가 그 곡선 따옴표다.
    expect(screen.getByText('지난 값 유지: 달라진 것 없음으로 이어간 기록'))
      .toBeInTheDocument();
    expect(screen.getByText('지켜보면 됨').parentElement).toHaveAttribute('data-carried', 'true');
  });

  it('판정 문구를 만들지 않는다', async () => {
    renderIt();
    await screen.findByText('화장실 이용');
    const main = screen.getByRole('main');

    // 금지어 나열이 아니라 전체를 화이트리스트로 건다(Home.test.tsx와 같은 방식).
    // 서버는 판정 필드를 주지 않으므로 화면에는 라벨과 주차만 있어야 한다 — 금지어
    // 목록에 없는 말이라도 한 글자라도 더 끼어들면 이 assertion이 걸린다.
    const toilet = data[0]!;
    const bathing = data[1]!;
    const toiletAxis = toilet.axes[0]!;
    const expected = [
      '← 뒤로',
      '전체 기록',
      '지난 값 유지: 달라진 것 없음으로 이어간 기록',
      toilet.label,
      toiletAxis.axisLabel,
      '좌우로 밀어 주차별 기록을 볼 수 있어요',
      ...toiletAxis.values.map((p, i) => `${p.week}주${p.label}${['직접 확인', '지난 값 유지', '직접 확인'][i]}`),
      `${bathing.label} — 바뀐 것 없음`, // —는 컴포넌트가 쓰는 em dash(—)다.
    ].join('');

    expect(main.textContent).toBe(expected);
  });
});
