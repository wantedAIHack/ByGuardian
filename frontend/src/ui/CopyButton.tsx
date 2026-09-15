import { useEffect, useRef, useState } from 'react';
import { Button } from './Button';
import { Notice } from './Notice';

export function CopyButton({ value, label }: { value: string; label: string }) {
  const [result, setResult] = useState<'idle' | 'pending' | 'done' | 'error'>('idle');
  const current = useRef(value);
  current.current = value;
  useEffect(() => setResult('idle'), [value]);
  async function copy() {
    setResult('pending');
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(value);
      if (current.current === value) setResult('done');
    } catch {
      if (current.current === value) setResult('error');
    }
  }
  return <div>
    <Button variant="plain" disabled={result === 'pending'} onClick={() => { void copy(); }}>{label}</Button>
    {result === 'done' ? <Notice role="status">복사했습니다</Notice> : null}
    {result === 'error' ? <Notice role="alert">복사하지 못했습니다. 표시된 내용을 직접 선택해 복사해 주세요.</Notice> : null}
  </div>;
}
