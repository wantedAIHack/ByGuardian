import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { Screen } from './Screen';

it('질문 이동에만 포커스를 옮기고 입력 중에는 유지한다', () => {
  const page = (key: number) => <Screen step={key} total={8} stageLabel="생활 관찰" focusKey={key}>
    <h1 data-step-title tabIndex={-1}>현재 질문</h1><input aria-label="메모" />
  </Screen>;
  const { rerender } = render(page(1));
  expect(screen.getByRole('heading')).toHaveFocus();
  const input = screen.getByRole('textbox');
  input.focus(); rerender(page(1)); expect(input).toHaveFocus();
  rerender(page(2)); expect(screen.getByRole('heading')).toHaveFocus();
  expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '2');
  expect(screen.getByText('2 / 8')).toBeVisible();
});

it('질문이 바뀔 때만 문서 스크롤을 맨 위로 돌린다', () => {
  const page = (key: number) => <Screen step={key} total={8} stageLabel="생활 관찰" focusKey={key}>
    <h1 data-step-title tabIndex={-1}>{key}번 질문</h1>
  </Screen>;
  const { rerender } = render(page(1));

  document.documentElement.scrollTop = 640;
  document.body.scrollTop = 640;
  rerender(page(1));
  expect(document.documentElement.scrollTop).toBe(640);
  expect(document.body.scrollTop).toBe(640);

  rerender(page(2));
  expect(document.documentElement.scrollTop).toBe(0);
  expect(document.body.scrollTop).toBe(0);
});
