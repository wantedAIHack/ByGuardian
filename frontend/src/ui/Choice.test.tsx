import { expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Choice } from './Choice';

it('선택을 표식과 aria 상태로 표시하고 선택만으로 이동하지 않는다', async () => {
  const onSelect = vi.fn();
  const { rerender } = render(<Choice label="혼자 하심" selected={false} onSelect={onSelect} />);
  const choice = screen.getByRole('button', { name: '혼자 하심' });
  expect(choice).toHaveAttribute('aria-pressed', 'false');
  expect(within(choice).queryByText('✓')).not.toBeInTheDocument();
  await userEvent.setup().click(choice);
  expect(onSelect).toHaveBeenCalledTimes(1);
  expect(choice).toHaveAttribute('aria-pressed', 'false');

  rerender(<Choice label="혼자 하심" selected onSelect={onSelect} />);
  expect(choice).toHaveAttribute('aria-pressed', 'true');
  expect(within(choice).getByText('✓')).toHaveAttribute('aria-hidden', 'true');
});
