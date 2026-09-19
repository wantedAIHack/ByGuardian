import { useId } from 'react';
import type { ItemInput, ObservationQuestion, Questionnaire } from '../lib/types';
import { questionnaireInput, questionVisible, sanitizeAnswers, selectAnswer } from '../lib/questionnaire';
import { Choice } from './Choice';
import { Notice } from './Notice';

export function QuestionnaireEditor({ form, value, onChange }: {
  form: Questionnaire; value: ItemInput; onChange: (value: ItemInput) => void;
}) {
  const id = useId();
  const input = questionnaireInput(form, value);
  const answers = sanitizeAnswers(form, input.answers ?? {});
  const renderQuestion = (q: ObservationQuestion) => (
    <fieldset className="min-w-0" aria-describedby={q.help ? `${id}-${q.code}-help` : undefined}>
      <legend className="font-semibold">
        {q.label}{!q.required ? <span className="text-small font-normal text-ink-soft"> (선택)</span> : null}
      </legend>
      {q.help ? <p id={`${id}-${q.code}-help`} className="pt-2 text-small text-ink-soft">{q.help}</p> : null}
      {q.kind === 'multiple' && !q.help ? <p className="pt-2 text-small text-ink-soft">해당하는 것을 모두 골라주세요.</p> : null}
      <div className="flex flex-col gap-3 pt-3">
        {q.options.map((option) => (
          <Choice key={option.code} label={option.label}
            selected={(answers[q.code] ?? []).includes(option.code)}
            onSelect={() => onChange({ ...input,
              answers: selectAnswer(form, answers, q.code, option.code) })} />
        ))}
      </div>
    </fieldset>
  );
  return (
    <div className="flex flex-col gap-8 pt-4">
      <Notice>이번 주에 직접 보신 모습 중 가장 흔했던 상황을 골라주세요. 보지 못했거나 하지 않은 활동도 그대로 선택하시면 됩니다.</Notice>
      {form.questions.filter((q) => questionVisible(q, answers)).map((q) => (
        !q.required && !q.when ? (
          <details key={q.code} className="rounded-lg border border-line p-4">
            <summary className="cursor-pointer py-2 font-semibold">
              {q.label} <span className="text-small font-normal text-ink-soft">(선택{answers[q.code]?.length ? ' · 답변 있음' : ''})</span>
            </summary>
            <div className="pt-4">{renderQuestion(q)}</div>
          </details>
        ) : <div key={q.code}>{renderQuestion(q)}</div>
      ))}
      <label className="block">
        <span className="text-small text-ink-soft">추가로 남길 관찰이 있나요? (선택, 500자까지)</span>
        <textarea className="mt-2 min-h-[112px] w-full rounded-lg border border-line p-4"
          rows={3} maxLength={500} value={value.note ?? ''}
          placeholder="선택지에 없는 상황이나 도움이 필요했던 부분을 적어주세요."
          onChange={(e) => onChange({ ...input, note: e.target.value })} />
        <span className="block pt-1 text-right text-small text-ink-faint">{value.note?.length ?? 0}/500</span>
      </label>
    </div>
  );
}
