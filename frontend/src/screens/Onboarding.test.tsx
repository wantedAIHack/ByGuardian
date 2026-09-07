import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Onboarding } from './Onboarding';
import { catalogFixture } from '../test/fixtures';
import { ONBOARDING_DRAFT, loadDraft } from '../lib/draft';
import type { OnboardingState } from '../lib/onboarding';

function renderScreen() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // 카탈로그는 컨텍스트로 주입한다. 화면 테스트는 네트워크를 타지 않는다.
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <Onboarding catalog={catalogFixture} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('온보딩 신원 단계', () => {
  it('안내로 시작하고 걸리는 시간을 미리 알린다', () => {
    renderScreen();
    expect(screen.getByText(/5분 정도 걸립니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '시작' })).toBeInTheDocument();
  });

  it('한 화면에 한 가지만 묻는다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));

    expect(screen.getByRole('button', { name: '딸' })).toBeInTheDocument();
    // 다음 단계의 선택지가 미리 보이지 않는다
    expect(screen.queryByRole('button', { name: '뇌졸중' })).not.toBeInTheDocument();
  });

  it('답하기 전에는 다음 버튼이 눌리지 않는다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));

    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '딸' }));
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
  });

  it('기타를 고르면 직접 입력 칸이 나온다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));
    await user.click(screen.getByRole('button', { name: '기타' }));

    const input = screen.getByLabelText('어떤 관계이신가요?');
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    await user.type(input, '손녀');
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
  });

  it('뒤로 가면 앞선 답이 남아 있다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));
    await user.click(screen.getByRole('button', { name: '딸' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '← 뒤로' }));

    expect(screen.getByRole('button', { name: '딸' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('매 단계 초안을 남긴다', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(screen.getByRole('button', { name: '시작' }));
    await user.click(screen.getByRole('button', { name: '딸' }));
    await user.click(screen.getByRole('button', { name: '다음' }));

    const draft = loadDraft<OnboardingState>(ONBOARDING_DRAFT);
    expect(draft?.relation).toBe('딸');
    expect(draft?.step).toBe(2);
  });

  it('초안이 있으면 그 단계에서 다시 연다', async () => {
    localStorage.setItem(ONBOARDING_DRAFT, JSON.stringify({
      step: 3, relation: '아들', relationOther: '', diagnosis: 'STROKE',
      pareticSide: null, verbalDifficulty: null, nextVisitDate: null, items: {},
    }));

    renderScreen();
    expect(screen.getByText('마비되신 쪽이 어디인가요?')).toBeInTheDocument();
  });

  it('외래일은 건너뛸 수 있다', async () => {
    const user = userEvent.setup();
    localStorage.setItem(ONBOARDING_DRAFT, JSON.stringify({
      step: 5, relation: '딸', relationOther: '', diagnosis: 'STROKE',
      pareticSide: 'LEFT', verbalDifficulty: 'NONE', nextVisitDate: null, items: {},
    }));

    renderScreen();
    await user.click(screen.getByRole('button', { name: '건너뛰기' }));
    expect(screen.getByText(/8가지로 한 번 여쭤보겠습니다/)).toBeInTheDocument();
  });
});
