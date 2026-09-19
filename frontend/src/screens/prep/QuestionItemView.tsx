import type { NoteBasis, PrepItem, QuestionOrigin } from '../../lib/types';
import { Collapse } from '../../ui/Collapse';
import { EvidenceView } from './EvidenceView';

export const ORIGIN_LABEL: Record<QuestionOrigin, string> = {
  LLM: '기록에서 정리',
  TEMPLATE: '관찰에서 나온 질문',
  CAREGIVER: '직접 적은 질문',
};

function noteHeading(note: NoteBasis): string {
  return [
    `${note.week}주`,
    note.timeTagLabel,
    note.itemLabel ? `${note.itemLabel} 메모` : null,
  ].filter(Boolean).join(' · ');
}

export function QuestionItemView({ item, index }: { item: PrepItem; index: number }) {
  const { evidence, notes } = item.basis;
  const hasEvidence = evidence.items.length > 0 || evidence.signal !== null;
  const hasNotes = notes.length > 0;

  return (
    <article className="note-surface" aria-labelledby={`question-${item.id}`}>
      <p className="mb-2 text-small text-ink-soft">
        질문 {index + 1} · {ORIGIN_LABEL[item.origin]}{item.edited ? ' · 고침' : ''}
      </p>
      <h3 id={`question-${item.id}`} className="text-[22px] font-semibold leading-snug">
        {item.sentence}
      </h3>
      {hasEvidence || hasNotes ? (
        <div className="pt-1">
          <Collapse label="이 질문의 근거">
            <div className="flex flex-col gap-5">
              {hasNotes ? (
                <div>
                  <p className="text-small text-ink-soft">보호자 기록</p>
                  <ul className="flex flex-col gap-2 pt-1">
                    {notes.map((note, i) => (
                      <li key={`${note.week}-${i}`}>
                        <p className="text-small text-ink-faint">{noteHeading(note)}</p>
                        <p className="whitespace-pre-wrap">{note.text}</p>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {hasEvidence ? (
                <div>
                  <p className="text-small text-ink-soft">관찰 기록</p>
                  <EvidenceView evidence={evidence} />
                </div>
              ) : null}
            </div>
          </Collapse>
        </div>
      ) : null}
    </article>
  );
}
