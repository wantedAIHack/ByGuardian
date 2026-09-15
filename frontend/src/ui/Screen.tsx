import { useEffect, useRef, type ReactNode } from 'react';

/** 진행 수치는 건강 상태가 아니라 지금 작성 중인 질문의 위치다. */
export function Screen({ step, total, stageLabel, focusKey, onBack, children, footer }: {
  step?: number; total?: number; stageLabel?: string; focusKey?: string | number;
  onBack?: () => void; children: ReactNode; footer?: ReactNode;
}) {
  const contentRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (focusKey === undefined) return;
    contentRef.current?.querySelector<HTMLElement>('[data-step-title]')?.focus();
  }, [focusKey]);
  const progress = step !== undefined && total !== undefined && total > 0;
  return (
    <main className="flow-shell mx-auto max-w-[600px] px-gutter">
      <header className="py-6">
        {onBack ? <button type="button" onClick={onBack} className="mb-4 min-h-[48px] text-ink-soft">← 뒤로</button> : null}
        <div className="flex items-center justify-between gap-4 text-small text-ink-soft">
          {stageLabel ? <span className="font-semibold">{stageLabel}</span> : null}
          {progress ? <span>{step} / {total}</span> : null}
        </div>
        {progress ? <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-paper-soft"
          role="progressbar" aria-label={stageLabel ?? '진행 단계'} aria-valuemin={0}
          aria-valuemax={total} aria-valuenow={step} aria-valuetext={`${total}개 중 ${step}번째`}>
          <div className="h-full rounded-full bg-accent" style={{ width: `${Math.min(100, Math.max(0, step / total * 100))}%` }} />
        </div> : null}
      </header>
      <div ref={contentRef} className="flow-content pt-2">{children}</div>
      {footer ? <footer className="flow-footer">{footer}</footer> : null}
    </main>
  );
}
