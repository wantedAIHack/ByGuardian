import type { ReactNode } from 'react';

/** 읽을 수 있는 대비로 보조 안내를 표시한다. 조작 결과는 해당 위치에서 알린다. */
export function Notice({ children, role }: { children: ReactNode; role?: 'status' | 'alert' }) {
  return <p role={role} className="text-small text-ink-soft">{children}</p>;
}
