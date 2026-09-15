export function Choice(
  { label, hint, selected, onSelect }:
  { label: string; hint?: string; selected: boolean; onSelect: () => void },
) {
  return (
    <button type="button" className="choice" aria-pressed={selected} onClick={onSelect}>
      <span className="choice-mark" aria-hidden="true">{selected ? '✓' : null}</span>
      <span className="min-w-0">
        {label}
        {hint ? <span className="block text-small text-ink-soft">{hint}</span> : null}
      </span>
    </button>
  );
}
