import { useId, useState, type ReactNode } from 'react';

export function Collapse({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <div>
      <button
        type="button"
        className="min-h-[48px] py-2 text-left text-small text-accent underline underline-offset-4"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((v) => !v)}
      >
        {open ? `${label} 접기` : label}
      </button>
      {open ? <div id={panelId} className="pt-2">{children}</div> : null}
    </div>
  );
}
