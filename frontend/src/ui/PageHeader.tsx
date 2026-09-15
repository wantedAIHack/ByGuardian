import { useEffect, useRef } from 'react';
import { Link } from 'react-router';

export function PageHeader({ title, backTo, settings = false, focusKey }: {
  title: string; backTo?: string; settings?: boolean; focusKey?: string | number;
}) {
  const heading = useRef<HTMLHeadingElement>(null);

  // 데이터 갱신 중 입력을 방해하지 않는다. 화면·단계 전환 키만 포커스를 옮긴다.
  useEffect(() => {
    if (focusKey !== undefined) heading.current?.focus();
  }, [focusKey]);

  return (
    <header className="mb-6 space-y-3">
      {backTo !== undefined ? (
        <Link to={backTo} className="inline-flex min-h-[48px] items-center gap-2 text-small text-accent">
          <span aria-hidden="true">←</span> 뒤로
        </Link>
      ) : null}
      <div className="flex items-start justify-between gap-4">
        <h1 ref={heading} tabIndex={focusKey !== undefined ? -1 : undefined}
          className="min-w-0 text-title font-semibold leading-[1.3] tracking-tight">{title}</h1>
        {settings ? (
          <Link to="/settings" className="inline-flex min-h-[48px] shrink-0 items-center px-2 text-small text-accent underline underline-offset-4">
            설정
          </Link>
        ) : null}
      </div>
    </header>
  );
}
