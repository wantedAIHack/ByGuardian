import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { PageHeader } from './PageHeader';

it('최초와 단계 키 변경에만 제목으로 포커스를 옮긴다', () => {
  const page = (key: string, title: string) => <MemoryRouter>
    <PageHeader title={title} focusKey={key} />
    <input aria-label="추가 질문" />
  </MemoryRouter>;
  const { rerender } = render(page('first', '관찰 남기기'));
  expect(screen.getByRole('heading', { level: 1 })).toHaveFocus();

  const input = screen.getByRole('textbox', { name: '추가 질문' });
  input.focus();
  rerender(page('first', '관찰 남기기 · 내용 갱신'));
  expect(input).toHaveFocus();

  rerender(page('second', '질문 준비하기'));
  expect(screen.getByRole('heading', { level: 1, name: '질문 준비하기' })).toHaveFocus();
});

it('포커스 키가 없으면 제목 갱신이 입력 포커스를 가져가지 않는다', () => {
  const { rerender } = render(<><PageHeader title="전체 기록" /><input aria-label="검색" /></>);
  expect(screen.getByRole('heading', { level: 1 })).not.toHaveFocus();
  const input = screen.getByRole('textbox');
  input.focus();
  rerender(<><PageHeader title="전체 기록 · 갱신" /><input aria-label="검색" /></>);
  expect(input).toHaveFocus();
});

it('뒤로와 설정 진입을 글자 라벨이 있는 링크로 제공한다', () => {
  render(<MemoryRouter><PageHeader title="관찰 노트" backTo="/" settings /></MemoryRouter>);
  expect(screen.getByRole('link', { name: /뒤로/ })).toHaveAttribute('href', '/');
  expect(screen.getByRole('link', { name: '설정' })).toHaveAttribute('href', '/settings');
});
