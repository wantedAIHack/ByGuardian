import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { NotFound } from './NotFound';
it('잘못된 주소에서 설명과 홈으로 돌아갈 길에 접근한다', () => {
  render(<MemoryRouter><NotFound /></MemoryRouter>);
  expect(screen.getByRole('heading', { level: 1 })).toHaveFocus();
  expect(screen.getByRole('link', { name: '홈으로' })).toHaveAttribute('href', '/');
});
