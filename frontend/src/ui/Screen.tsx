import type { ReactNode } from 'react';

/**
 * 전체 화면 흐름(온보딩·주간 기록)의 껍데기.
 * 진행 막대는 현재 단계 표시이지 상태 측정이 아니다. §2의 "숫자 금지"에 걸리지 않는다.
 */
export function Screen(
  { step, total, onBack, children, footer }:
  { step?: number; total?: number; onBack?: () => void; children: ReactNode; footer?: ReactNode },
) {
  // 좁히기를 명시적으로 한다. 별칭 조건에 기대면 strict 설정에서 걸릴 수 있다.
  const percent =
    step !== undefined && total !== undefined && total > 0
      ? Math.round((step / total) * 100)
      : null;
  return (
    <div className="mx-auto flex min-h-full max-w-lg flex-col px-gutter pb-gutter">
      <div className="flex min-h-[56px] items-center gap-3 pt-2">
        {onBack ? (
          <button type="button" onClick={onBack} className="min-h-[48px] px-2 text-ink-soft">
            ← 뒤로
          </button>
        ) : null}
        {percent !== null ? (
          <div className="h-1 flex-1 rounded bg-paper-soft" role="presentation">
            <div className="h-1 rounded bg-accent" style={{ width: `${percent}%` }} />
          </div>
        ) : null}
      </div>
      <div className="flex-1 pt-4">{children}</div>
      {footer ? <div className="pt-6">{footer}</div> : null}
    </div>
  );
}
