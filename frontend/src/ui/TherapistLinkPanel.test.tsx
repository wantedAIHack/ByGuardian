import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { server } from '../test/server';
import { TherapistLinkPanel } from './TherapistLinkPanel';

const BASE = 'http://localhost:8080';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
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
      HttpResponse.json({ url: '/t/abc123', token: 'abc123' })));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));

    expect(await screen.findByText(`${window.location.origin}/t/abc123`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '치료사에게 보여드리기' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '어떻게 보이는지 확인하기' }))
      .toHaveAttribute('href', `${window.location.origin}/t/abc123`);
  });

  it('주소는 서버가 준 상대 url이 아니라 프론트 오리진으로 조립한 절대 주소다', async () => {
    const user = userEvent.setup();
    // url과 token을 일부러 어긋나게 준다. 컴포넌트가 url을 그대로 쓰면 화면에
    // '/t/서버가-잘못-준-경로'가 뜨고, token으로 조립하면 origin이 붙은 abc123이 뜬다.
    server.use(http.post(`${BASE}/me/therapist-link`, () =>
      HttpResponse.json({ url: '/t/서버가-잘못-준-경로', token: 'abc123' })));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));

    const address = await screen.findByText(`${window.location.origin}/t/abc123`, { exact: true });
    expect(address).toBeInTheDocument();
    expect(screen.queryByText(/서버가-잘못-준-경로/)).not.toBeInTheDocument();
  });

  it('복사 버튼은 화면에 보이는 절대 주소를 그대로 복사한다', async () => {
    const user = userEvent.setup();
    const writeText = stubClipboard();
    server.use(http.post(`${BASE}/me/therapist-link`, () =>
      HttpResponse.json({ url: '/t/xyz789', token: 'xyz789' })));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));
    await screen.findByText(`${window.location.origin}/t/xyz789`);
    await user.click(screen.getByRole('button', { name: '주소 복사하기' }));

    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/t/xyz789`);
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
        calls === 1 ? { url: '/t/first', token: 'first-token' } : { url: '/t/second', token: 'second-token' },
      );
    }));
    renderPanel();

    await user.click(screen.getByRole('button', { name: '치료사에게 보여드리기' }));
    expect(await screen.findByText(`${window.location.origin}/t/first-token`)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '주소 복사하기' }));
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/t/first-token`);
    expect(await screen.findByRole('button', { name: '복사했습니다' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '새 주소 만들기' }));

    expect(await screen.findByText(`${window.location.origin}/t/second-token`)).toBeInTheDocument();
    expect(screen.queryByText(`${window.location.origin}/t/first-token`)).not.toBeInTheDocument();
    // 새 주소가 왔으니 '복사했습니다'는 지난 주소 얘기다. 되돌아가 있어야 한다.
    expect(screen.getByRole('button', { name: '주소 복사하기' })).toBeInTheDocument();
    expect(calls).toBe(2);
  });
});
