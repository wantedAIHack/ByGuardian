import type { ReactNode } from 'react';

/** 회색 안내 한 줄. 강조하지 않는다. */
export function Notice({ children }: { children: ReactNode }) {
  return <p className="text-small text-ink-soft">{children}</p>;
}
