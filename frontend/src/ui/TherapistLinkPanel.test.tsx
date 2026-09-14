import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { setToken, setTokenChangeHandler } from '../lib/api';
import { clearTherapistLinkOnTokenChange } from '../lib/queries';
import { TherapistLinkPanel } from './TherapistLinkPanel';

const BASE = 'http://localhost:8080';
const TOKEN_A = '123e4567-e89b-12d3-a456-426614174000';
const TOKEN_B = '223e4567-e89b-12d3-a456-426614174001';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
// setTokenChangeHandler는 api.ts 모듈 전역이라(setUnauthorizedHandler와 같은 자리) 등록한
// 테스트가 끝나면 지운다 — 안 지우면 다음 테스트가 이 파일의 이전 QueryClient를 가리키는
// 낡은 핸들러를 물려받는다.
afterEach(() => setTokenChangeHandler(null));
afterAll(() => server.close());

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <TherapistLinkPanel />
    </QueryClientProvider>,
  );
}

/** jsdom은 Clipboard API를 구현하지 않는다. 복사 관련 테스트마다 직접 채워 넣는다. */
function stubClipboard() {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(window.navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  });
  return writeText;
}

describe('TherapistLinkPanel', () => {
  it('버튼을 누르면 링크를 발급한다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/me/therapist-link`, () =>
      HttpResponse.json({ url: `/t/${TOKEN_A}`, token: TOKEN_A })));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));

    expect(await screen.findByText(`${window.location.origin}/t#${TOKEN_A}`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '치료사에게 보여드리기' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '어떻게 보이는지 확인하기' }))
      .toHaveAttribute('href', `${window.location.origin}/t#${TOKEN_A}`);
  });

  it('주소는 서버가 준 상대 url이 아니라 프론트 오리진으로 조립한 절대 주소다', async () => {
    const user = userEvent.setup();
    // url과 token을 일부러 어긋나게 준다. 컴포넌트가 url을 그대로 쓰면 화면에
    // '/t/서버가-잘못-준-경로'가 뜨고, token으로 조립하면 fragment에 UUID가 붙는다.
    server.use(http.post(`${BASE}/me/therapist-link`, () =>
      HttpResponse.json({ url: '/t/서버가-잘못-준-경로', token: TOKEN_A })));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));

    const address = await screen.findByText(`${window.location.origin}/t#${TOKEN_A}`, { exact: true });
    expect(address).toBeInTheDocument();
    expect(screen.queryByText(/서버가-잘못-준-경로/)).not.toBeInTheDocument();
  });

  it('복사 버튼은 화면에 보이는 절대 주소를 그대로 복사한다', async () => {
    const user = userEvent.setup();
    const writeText = stubClipboard();
    server.use(http.post(`${BASE}/me/therapist-link`, () =>
      HttpResponse.json({ url: `/t/${TOKEN_A}`, token: TOKEN_A })));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));
    await screen.findByText(`${window.location.origin}/t#${TOKEN_A}`);
    await user.click(screen.getByRole('button', { name: '주소 복사하기' }));

    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/t#${TOKEN_A}`);
    expect(await screen.findByRole('button', { name: '복사했습니다' })).toBeInTheDocument();
  });

  it('발급에 실패하면 안내 문구를 보여준다', async () => {
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/me/therapist-link`, () =>
      HttpResponse.json({ code: 'FAIL', message: '실패' }, { status: 500 })));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));

    expect(await screen.findByText('링크를 만들지 못했습니다. 잠시 후 다시 눌러주세요.')).toBeInTheDocument();
    // 실패해도 처음 버튼은 그대로 남아 다시 누를 수 있어야 한다
    expect(screen.getByRole('button', { name: '치료사에게 보여드리기' })).toBeInTheDocument();
  });

  it('발급한 뒤에도 새 주소를 만들 수 있고, 복사 상태도 새로 시작한다', async () => {
    const user = userEvent.setup();
    const writeText = stubClipboard();
    let calls = 0;
    server.use(http.post(`${BASE}/me/therapist-link`, () => {
      calls += 1;
      return HttpResponse.json(
        calls === 1 ? { url: `/t/${TOKEN_A}`, token: TOKEN_A } : { url: `/t/${TOKEN_B}`, token: TOKEN_B },
      );
    }));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));
    expect(await screen.findByText(`${window.location.origin}/t#${TOKEN_A}`)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '주소 복사하기' }));
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/t#${TOKEN_A}`);
    expect(await screen.findByRole('button', { name: '복사했습니다' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '새 주소 만들기' }));

    expect(await screen.findByText(`${window.location.origin}/t#${TOKEN_B}`)).toBeInTheDocument();
    expect(screen.queryByText(`${window.location.origin}/t#${TOKEN_A}`)).not.toBeInTheDocument();
    // 새 주소가 왔으니 '복사했습니다'는 지난 주소 얘기다. 되돌아가 있어야 한다.
    expect(screen.getByRole('button', { name: '주소 복사하기' })).toBeInTheDocument();
    expect(calls).toBe(2);
  });

  // 홈·준비 카드·설정 세 화면이 각자 TherapistLinkPanel을 마운트한다(같은 QueryClient
  // 아래에서). 링크가 컴포넌트 로컬 뮤테이션 상태였을 때는 화면마다 "발급 전"부터 다시
  // 시작해, 한 화면에서 이미 치료사에게 전달한 주소를 모른 채 다른 화면에서 또 발급하면
  // 방금 전달한 주소가 그 자리에서 죽었다(재발급은 이전 토큰을 죽인다 —
  // TherapistControllerTest.reissueRevokesPreviousLink). 아래는 그 시나리오를 두 패널을
  // 동시에 마운트해 재현한다: 한쪽에서 발급하면 다른 쪽도 같은 주소를 반영해야 하고,
  // 이미 링크를 아는 쪽이 그걸 모른 채 다시 발급 요청을 보내서는 안 된다.
  it('같은 QueryClient 아래 두 패널이 같은 링크를 보여주고, 발급 요청은 한 번만 나간다', async () => {
    const user = userEvent.setup();
    let calls = 0;
    server.use(http.post(`${BASE}/me/therapist-link`, () => {
      calls += 1;
      return HttpResponse.json({ url: `/t/${TOKEN_A}`, token: TOKEN_A });
    }));

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <div data-testid="panel-a"><TherapistLinkPanel /></div>
        <div data-testid="panel-b"><TherapistLinkPanel /></div>
      </QueryClientProvider>,
    );

    const buttons = screen.getAllByRole('button', { name: '치료사에게 보여드리기' });
    expect(buttons).toHaveLength(2);
    await user.click(buttons[0]!);

    // 발급을 요청한 패널과, 요청하지 않은 다른 패널 둘 다 같은 주소를 보여줘야 한다.
    const addresses = await screen.findAllByText(`${window.location.origin}/t#${TOKEN_A}`);
    expect(addresses).toHaveLength(2);
    // 이미 링크를 아는 두 번째 패널에는 발급 전 화면('치료사에게 보여드리기' 버튼)이
    // 다시 나타나지 않는다.
    expect(screen.queryByRole('button', { name: '치료사에게 보여드리기' })).not.toBeInTheDocument();
    expect(calls).toBe(1);
  });

  it('토큰이 바뀌면 이전 케이스의 링크를 지우고 새로 발급하게 한다', async () => {
    // 재발급 라운드 리뷰에서 나온 회귀: QueryClient는 앱 수명 내내 하나뿐이고(main.tsx)
    // 링크 쿼리는 enabled:false + gcTime:Infinity라 스스로 만료되지 않는다. 토큰이 바뀔
    // 때(온보딩·이어받기·데모·401) 아무도 이 캐시를 지우지 않으면, 새로 이어받은 다른
    // 케이스의 화면에 이전 케이스의 /t/<token>이 그대로 뜨고 — url이 truthy라 발급
    // 버튼 자체가 숨어 새 보호자는 자기 링크를 낼 방법이 없다.
    const user = userEvent.setup();
    server.use(http.post(`${BASE}/me/therapist-link`, () =>
      HttpResponse.json({ url: `/t/${TOKEN_A}`, token: TOKEN_A })));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    // main.tsx가 시작할 때 하는 배선을 그대로 재현한다 — setToken/clearToken을 부르는
    // 진입점이라면 어디서 불러도 이 등록 하나로 잡힌다.
    clearTherapistLinkOnTokenChange(qc);

    const first = render(
      <QueryClientProvider client={qc}>
        <TherapistLinkPanel />
      </QueryClientProvider>,
    );

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));
    await screen.findByText(`${window.location.origin}/t#${TOKEN_A}`);

    // 실제 화면 전환을 그대로 재현한다: 홈이 내려가고(이어받기·온보딩·데모로 이동),
    // 앱이 토큰 진입점에서 실제로 부르는 그 함수로 토큰이 바뀐 뒤(테스트에서
    // qc.removeQueries를 직접 부르는 게 아니라 실제 신호 경로를 그대로 태운다),
    // 새 케이스로 홈이 다시 뜬다 — 그때 패널도 새로 마운트된다.
    first.unmount();
    setToken('new-guardian-token');

    render(
      <QueryClientProvider client={qc}>
        <TherapistLinkPanel />
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('button', { name: '치료사에게 보여드리기' })).toBeInTheDocument();
    expect(screen.queryByText(`${window.location.origin}/t#${TOKEN_A}`)).not.toBeInTheDocument();
  });
});
