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
    // 노드를 갖는다.
    //
    // 이 화이트리스트는 기본 상태만 본다 — reissue.data 분기와 confirming 분기는
    // 조건부로만 렌더되어 여기 안 걸린다. reissue.data 분기는 아래의 별도 화이트리스트
    // ('판정 문구를 만들지 않는다 — 재발급된 코드')가 덮는다. confirming 분기는
    // 두 문장을 각각 정규식으로 고정한 '재발급은 확인을 거치고...' 테스트가 있다.
    expect(container.textContent).toBe(
      [
        '← 홈',
        '설정',
        '다른 가족 초대하기',
        '지금 갖고 계신 이어받기 코드를 알려주시면 됩니다. 받으신 분이 ‘이어받기’에서 그 코드와 자신의 관계를 넣으면 같은 기록에 함께 남기실 수 있습니다.',
        '새 코드를 만들 필요가 없습니다. 누가 남긴 기록인지는 치료사용 요약에 함께 나갑니다.',
        // 이 기기에 코드가 없을 때의 안내. 테스트는 저장소가 빈 상태로 시작한다.
        '이 기기에는 코드가 저장돼 있지 않습니다. 적어두신 코드를 알려주시거나, 찾지 못하셨다면 아래에서 새로 만드실 수 있습니다.',
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

  it('판정 문구를 만들지 않는다 — 재발급된 코드', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/me/recovery-code`, () =>
      HttpResponse.json({ recoveryCode: 'AB23CD45' })));

    const { container } = renderIt();
    await screen.findByText('복구 코드 다시 만들기');
    await user.click(screen.getByRole('button', { name: '코드 새로 만들기' }));
    await user.click(screen.getByRole('button', { name: '네, 새로 만들겠습니다' }));
    await screen.findByText('AB23CD45');

    // 정책이 '화면당 하나'에서 '도달 가능한 상태마다 하나'로 바뀐 계기가 이 상태다.
    // reissue.data 분기는 기본 상태의 화이트리스트가 구조적으로 볼 수 없고, 조작된
    // 문구가 앉기 가장 위험한 자리다 — 캐어기버에게 그대로 적어두라고 말하는 평문
    // 복구 코드 바로 옆이다. 위 화이트리스트와 마찬가지로 '다음 진료일'이 두 번
    // 나오는 것은 실수가 아니다(보이는 h2 제목 + 입력칸의 sr-only 라벨).
    expect(container.textContent).toBe(
      [
        '← 홈',
        '설정',
        '다른 가족 초대하기',
        '지금 갖고 계신 이어받기 코드를 알려주시면 됩니다. 받으신 분이 ‘이어받기’에서 그 코드와 자신의 관계를 넣으면 같은 기록에 함께 남기실 수 있습니다.',
        '새 코드를 만들 필요가 없습니다. 누가 남긴 기록인지는 치료사용 요약에 함께 나갑니다.',
        // 재발급 직후에는 이 기기에 새 코드가 저장돼 있으므로 안내 대신
        // 꺼내 보는 버튼이 나온다. 그것이 이 기능의 요점이다.
        '지금 코드 보기',
        '복구 코드 다시 만들기',
        '적어두신 코드를 잃으셨을 때만 쓰세요.',
        'AB23CD45',
        '지금 적어두시거나 사진을 찍어두세요. 다시 보여드릴 수 없습니다.',
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

  it('판정 문구를 만들지 않는다 — 재발급 확인 상태', async () => {
    // confirming 분기는 위 두 화이트리스트 어느 쪽도 못 본다 — 기본 상태의 화이트리스트는
    // 조건부로 안 열려 있고, reissue.data 화이트리스트는 이미 새 코드가 나온 다음이다.
    // 이 상태(재발급을 누른 직후, 아직 확인 전)는 '재발급은 확인을 거치고...' 테스트가
    // 정규식 두 개로만 지켜 왔다 — 여기서 전체를 화이트리스트로 건다.
    const user = userEvent.setup();
    const { container } = renderIt();
    await screen.findByText('복구 코드 다시 만들기');
    await user.click(screen.getByRole('button', { name: '코드 새로 만들기' }));
    await screen.findByText(/지금 코드는 더 이상 쓸 수 없게 됩니다/);

    expect(container.textContent).toBe(
      [
        '← 홈',
        '설정',
        '다른 가족 초대하기',
        '지금 갖고 계신 이어받기 코드를 알려주시면 됩니다. 받으신 분이 ‘이어받기’에서 그 코드와 자신의 관계를 넣으면 같은 기록에 함께 남기실 수 있습니다.',
        '새 코드를 만들 필요가 없습니다. 누가 남긴 기록인지는 치료사용 요약에 함께 나갑니다.',
        // 이 기기에 코드가 없을 때의 안내. 테스트는 저장소가 빈 상태로 시작한다.
        '이 기기에는 코드가 저장돼 있지 않습니다. 적어두신 코드를 알려주시거나, 찾지 못하셨다면 아래에서 새로 만드실 수 있습니다.',
        '복구 코드 다시 만들기',
        '적어두신 코드를 잃으셨을 때만 쓰세요.',
        '지금 코드는 더 이상 쓸 수 없게 됩니다.',
        '그 코드로 초대하신 분도 새로 이어받으셔야 합니다.',
        '네, 새로 만들겠습니다',
        '그만두기',
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
