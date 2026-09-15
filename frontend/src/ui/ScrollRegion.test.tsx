import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ScrollRegion } from './ScrollRegion';
it('키보드 사용자가 이름과 연결된 안내로 기록 영역을 찾는다', () => {
  render(<ScrollRegion label="걷기 주차별 기록"><p>6주 기록</p></ScrollRegion>);
  const region = screen.getByRole('region', { name: '걷기 주차별 기록' });
  expect(region).toHaveAttribute('tabindex', '0');
  expect(region).toHaveAccessibleDescription('좌우로 밀어 주차별 기록을 볼 수 있어요');
  region.focus();
  expect(region).toHaveFocus();
  expect(region).toHaveTextContent('6주 기록');
});
