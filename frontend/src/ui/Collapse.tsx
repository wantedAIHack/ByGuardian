import { useState, type ReactNode } from 'react';

export function Collapse({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        type="button"
        className="min-h-[48px] text-small text-accent underline"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {open ? `${label} 접기` : label}
      </button>
      {open ? <div className="pt-2">{children}</div> : null}
    </div>
  );
}
