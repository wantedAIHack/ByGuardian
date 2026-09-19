import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { Demo } from './Demo';

function renderIt() {
  return render(<MemoryRouter><Demo /></MemoryRouter>);
}

describe('데모', () => {
  it('실제 온보딩에서 1주차 기록을 시작하도록 안내한다', () => {
    renderIt();
    expect(screen.getByText(/온보딩과 1주차 관찰부터 직접/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '온보딩부터 시작하기' }))
      .toHaveAttribute('href', '/demo/onboarding');
  });
});
