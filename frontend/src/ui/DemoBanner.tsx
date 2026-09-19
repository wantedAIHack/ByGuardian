import { useRef } from 'react';
import { useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError, clearDemoToken } from '../lib/api';
import { DEMO_ONBOARDING_DRAFT, clearDraft } from '../lib/draft';
import { useAdvanceDemo } from '../lib/queries';
import type { Me } from '../lib/types';
import { Button } from './Button';

export function DemoBanner({ me }: { me: Me }) {
  const advance = useAdvanceDemo();
  // 이동 응답은 부모의 /me 쿼리가 따라오기 전까지만 쓴다. 같은 주차의 주간 기록을
  // 저장한 뒤에는 새 /me의 canAdvanceDemo=true가 더 최신 상태다.
  const current = advance.data
    && (advance.data.week > me.week || advance.data.today !== me.today) ? advance.data : me;
  const locked = useRef(false);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const canAdvance = current.canAdvanceDemo === true;

  const next = () => {
    if (!canAdvance || locked.current) return;
    locked.current = true;
    advance.mutate(undefined, { onSettled: () => { locked.current = false; } });
  };

  const exitDemo = () => {
    clearDemoToken();
    clearDraft(DEMO_ONBOARDING_DRAFT);
    qc.removeQueries({ queryKey: ['me'] });
    navigate('/', { replace: true });
  };

  return <aside className="border-b border-line bg-paper-soft px-4 py-4" aria-label="데모 진행">
    <div className="mx-auto flex max-w-[720px] flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="font-semibold">데모 모드 · <span>{current.week}주차</span></p>
        <p className="text-small text-ink-soft">가상 날짜 {current.today.replaceAll('-', '.')}</p>
      </div>
      {!canAdvance
        ? <p className="text-small text-ink-soft">현재 주차 기록을 남기면 다음 주로 이동할 수 있습니다.</p>
        : null}
      {advance.error ? <p role="alert" className="text-small">
        {advance.error instanceof ApiError ? advance.error.message : '날짜를 진행하지 못했습니다.'}
      </p> : null}
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <Button disabled={!canAdvance || advance.isPending} onClick={next}>
          {advance.isPending ? '이동 중입니다…' : '다음 주차로 이동'}
        </Button>
        <Button variant="quiet" onClick={exitDemo}>데모 종료</Button>
      </div>
    </div>
  </aside>;
}
