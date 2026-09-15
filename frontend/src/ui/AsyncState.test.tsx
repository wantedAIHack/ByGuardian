import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AsyncState } from './AsyncState';

it('로딩은 상태로 알리고 재시도 행동은 표시하지 않는다', () => {
  render(<AsyncState kind="loading" message="기록을 불러오는 중입니다" onRetry={vi.fn()} />);
  expect(screen.getByRole('status')).toHaveTextContent('기록을 불러오는 중입니다');
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
});

it('오류는 경고로 알리고 다시 시도할 수 있다', async () => {
  const onRetry = vi.fn();
  render(<AsyncState kind="error" message="연결하지 못했습니다" onRetry={onRetry} />);
  expect(screen.getByRole('alert')).toHaveTextContent('연결하지 못했습니다');
  await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도하기' }));
  expect(onRetry).toHaveBeenCalledTimes(1);
});

it('재시도 동작이 없는 오류는 작동하지 않는 버튼을 만들지 않는다', () => {
  render(<AsyncState kind="error" message="이 기록을 열 수 없습니다" />);
  expect(screen.getByRole('alert')).toBeVisible();
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
});
