import { afterEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CopyButton } from './CopyButton';
afterEach(() => vi.restoreAllMocks());
it('복사 거부를 성공으로 표시하지 않는다', async () => {
  const user = userEvent.setup();
  vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValueOnce(new Error('denied'));
  render(<CopyButton value="TEST1234" label="코드 복사하기" />);
  await user.click(screen.getByRole('button', { name: '코드 복사하기' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('직접 선택해 복사해 주세요');
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
});
it('복사한 실제 값을 확인하고 새 값이면 성공 안내를 초기화한다', async () => {
  const user = userEvent.setup();
  const { rerender } = render(<CopyButton value="TEST1234" label="코드 복사하기" />);
  await user.click(screen.getByRole('button', { name: '코드 복사하기' }));
  expect(await navigator.clipboard.readText()).toBe('TEST1234');
  expect(await screen.findByRole('status')).toHaveTextContent('복사했습니다');
  rerender(<CopyButton value="NEW12345" label="코드 복사하기" />);
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
});
it('클립보드가 없는 환경에서 수동 복사를 안내한다', async () => {
  const user = userEvent.setup();
  vi.spyOn(navigator, 'clipboard', 'get').mockReturnValue(undefined as unknown as Clipboard);
  render(<CopyButton value="TEST1234" label="코드 복사하기" />);
  await user.click(screen.getByRole('button', { name: '코드 복사하기' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('직접 선택해 복사해 주세요');
});
