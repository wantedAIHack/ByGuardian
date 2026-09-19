import type { PrepCard } from '../../lib/types';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';

export function QuestionStatus({
  card,
  editing,
  regenerating,
  regenerateFailed,
  onRegenerate,
}: {
  card: PrepCard;
  editing: boolean;
  regenerating: boolean;
  regenerateFailed: boolean;
  onRegenerate: () => void;
}) {
  const items = card.items ?? [];
  const pending = card.generationStatus === 'PENDING';
  const templateOnly = !card.edited && items.length > 0
    && (card.generationStatus === 'FAILED' || card.generationStatus === 'TEMPLATE_ONLY');

  return (
    <div aria-live="polite" className="flex flex-col gap-3">
      {pending && !editing
        ? <Notice role="status">기록을 바탕으로 질문을 정리하고 있어요.</Notice>
        : null}
      {templateOnly
        ? <Notice role="status">관찰 기록에서 나온 질문을 보여드려요.</Notice>
        : null}
      {card.suggestionAvailable && !editing ? (
        <div className="note-surface flex flex-col gap-3">
          <p>새 기록이 반영된 정리안이 있어요.</p>
          <Button disabled={regenerating} onClick={onRegenerate}>
            {regenerating ? '정리하는 중입니다…' : '다시 정리하기'}
          </Button>
          <p className="text-small text-ink-soft">직접 적으신 질문은 그대로 남아요.</p>
        </div>
      ) : null}
      {regenerateFailed
        ? <Notice role="alert">다시 정리하지 못했습니다. 잠시 뒤 다시 눌러 주세요.</Notice>
        : null}
    </div>
  );
}
