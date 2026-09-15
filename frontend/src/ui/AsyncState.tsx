import { Button } from './Button';

export function AsyncState({ kind, message, onRetry }: {
  kind: 'loading' | 'error'; message: string; onRetry?: () => void;
}) {
  return (
    <div className="note-surface space-y-4">
      <p role={kind === 'error' ? 'alert' : 'status'}>{message}</p>
      {kind === 'error' && onRetry ? (
        <Button variant="plain" onClick={onRetry}>다시 시도하기</Button>
      ) : null}
    </div>
  );
}
