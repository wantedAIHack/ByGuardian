import type { PrepCard } from '../../lib/types';
import { Collapse } from '../../ui/Collapse';
import { EvidenceView } from './EvidenceView';

export function LegacyQuestions({ card }: { card: PrepCard }) {
  return (
    <>
      {card.questions.length === 0 ? (
        card.emptyMessage ? <p className="pt-8">{card.emptyMessage}</p> : null
      ) : (
        <ol className="space-y-5">
          {card.questions.map((question) => (
            <li key={question.rank} className="min-w-0">
              <article className="note-surface" aria-labelledby={`question-${question.rank}`}>
                <p className="mb-2 text-small text-ink-soft">질문 {question.rank}</p>
                <h2
                  id={`question-${question.rank}`}
                  className="text-[22px] font-semibold leading-snug"
                >
                  {question.sentence}
                </h2>
                <div className="pt-1">
                  <Collapse label="이 질문의 관찰 근거">
                    <EvidenceView evidence={question.evidence} />
                  </Collapse>
                </div>
              </article>
            </li>
          ))}
        </ol>
      )}
      {card.extraQuestions.length > 0 ? (
        <section className="note-surface">
          <h2 className="font-semibold">내가 더 여쭤보고 싶은 것</h2>
          <ul className="list-disc pt-3 pl-5">
            {card.extraQuestions.map((question, i) => (
              <li key={`${question}-${i}`}>{question}</li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}
