import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ObservationValue } from './ObservationValue';
it('유지한 값과 직접 확인한 값, 미기록을 색 없이 구분한다', () => {
  const { rerender } = render(<ObservationValue point={{ week: 6, value: 2, label: '지켜보면 됨', source: 'CARRIED' }} />);
  expect(screen.getByText('지켜보면 됨')).toBeVisible();
  expect(screen.getByText('지난 값 유지')).toBeVisible();
  rerender(<ObservationValue point={{ week: 6, value: 2, label: '지켜보면 됨', source: 'CONFIRMED' }} />);
  expect(screen.getByText('직접 확인')).toBeVisible();
  expect(screen.queryByText('지난 값 유지')).not.toBeInTheDocument();
  rerender(<ObservationValue />);
  expect(screen.getByText('미기록')).toBeVisible();
});
