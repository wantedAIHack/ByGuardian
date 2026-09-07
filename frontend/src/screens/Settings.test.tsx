import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { Settings } from './Settings';
import { me } from '../test/fixtures';
import { setToken } from '../lib/api';

const BASE = 'http://localhost:8080';
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderIt() {
  setToken('t');
  server.use(http.get(`${BASE}/me`, () => HttpResponse.json(me({ nextVisitDate: '2026-09-20' }))));
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><Settings /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('설정', () => {
  it('초대 구역과 재발급 구역이 갈라져 있다', async () => {
    renderIt();
    const invite = (await screen.findByText('다른 가족 초대하기')).closest('section')!;

    expect(within(invite).getByText(/지금 갖고 계신 이어받기 코드를 알려주시면 됩니다/))
      .toBeInTheDocument();
    // 초대 구역 안에 재발급 버튼이 있으면 안 된다.
    // 둘을 섞으면 온보딩에서 적어두게 한 코드가 초대 한 번에 죽는다.
    expect(within(invite).queryByRole('button', { name: '코드 새로 만들기' })).toBeNull();
    // 재발급은 자기 구역에 따로 있다
    expect(screen.getByRole('button', { name: '코드 새로 만들기' })).toBeInTheDocument();
  });

  it('재발급은 확인을 거치고 옛 코드가 죽는다고 먼저 알린다', async () => {
    const user = userEvent.setup();
    renderIt();
    await screen.findByText('복구 코드 다시 만들기');

    await user.click(screen.getByRole('button', { name: '코드 새로 만들기' }));
    expect(screen.getByText(/지금 코드는 더 이상 쓸 수 없게 됩니다/)).toBeInTheDocument();
    expect(screen.getByText(/그 코드로 초대하신 분도 새로 이어받으셔야 합니다/)).toBeInTheDocument();
  });

  it('확인하면 새 코드를 보여준다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/me/recovery-code`, () =>
      HttpResponse.json({ recoveryCode: 'AB23CD45' })));

    renderIt();
    await screen.findByText('복구 코드 다시 만들기');
    await user.click(screen.getByRole('button', { name: '코드 새로 만들기' }));
    await user.click(screen.getByRole('button', { name: '네, 새로 만들겠습니다' }));

    expect(await screen.findByText('AB23CD45')).toBeInTheDocument();
    expect(screen.getByText(/다시 보여드릴 수 없습니다/)).toBeInTheDocument();
  });

  it('다음 진료일을 바꾼다', async () => {
    const user = userEvent.setup();
    let sent: any = null;
    server.use(http.patch(`${BASE}/me`, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(me({ nextVisitDate: '2026-10-01' }));
    }));

    renderIt();
    const input = await screen.findByLabelText('다음 진료일');
    await user.clear(input);
    await user.type(input, '2026-10-01');
    await user.click(screen.getByRole('button', { name: '진료일 저장' }));

    expect(sent.nextVisitDate).toBe('2026-10-01');
  });

  it('주간 알림 파일을 다시 받을 수 있다', async () => {
    renderIt();
    expect(await screen.findByRole('button', { name: '캘린더 파일 다시 받기' })).toBeInTheDocument();
  });

  it('판정 문구를 만들지 않는다', async () => {
    const { container } = renderIt();
    await screen.findByText('복구 코드 다시 만들기');
    await screen.findByRole('button', { name: '치료사에게 보여드리기' });

    // 금지어 나열이 아니라 전체를 화이트리스트로 건다(Home.test.tsx·Trajectory.test.tsx·
    // PrepCard.test.tsx와 같은 방식). 이 화면이 보여주는 진료일은 <input value=...>뿐이라
    // textContent에 안 잡힌다(입력 요소는 자식 텍스트 노드를 가질 수 없다) — 그래서
    // renderIt이 넣어준 nextVisitDate(2026-09-20)는 기대 문자열에 나오지 않는다.
    // '다음 진료일'이 두 번 나오는 것은 실수가 아니다 — 보이는 h2 제목과, 입력칸에
    // 붙은 sr-only 라벨(시각 라벨은 h2가 대신하므로 화면엔 숨어 있다) 둘 다 텍스트
    // 노드를 갖는다. 재발급으로 새 코드를 받거나 확인 중인 상태는 별도 테스트가
    // 이미 정확한 문구로 고정하고 있다 — 여기는 캐어기버가 맨 처음 보는, 초대와
    // 재발급이 갈라져 있는 바로 그 상태를 고정한다.
    expect(container.textContent).toBe(
      [
        '← 홈',
        '설정',
        '다른 가족 초대하기',
        '지금 갖고 계신 이어받기 코드를 알려주시면 됩니다. 받으신 분이 ‘이어받기’에서 그 코드와 자신의 관계를 넣으면 같은 기록에 함께 남기실 수 있습니다.',
        '새 코드를 만들 필요가 없습니다. 누가 남긴 기록인지는 치료사용 요약에 함께 나갑니다.',
        '복구 코드 다시 만들기',
        '적어두신 코드를 잃으셨을 때만 쓰세요.',
        '코드 새로 만들기',
        '다음 진료일',
        '다음 진료일',
        '진료일 저장',
        '주간 알림',
        '쓰시는 달력에 매주 반복 일정을 넣어드립니다.',
        '캘린더 파일 다시 받기',
        '치료사 링크',
        '치료사에게 보여드리기',
      ].join(''),
    );
  });
});
