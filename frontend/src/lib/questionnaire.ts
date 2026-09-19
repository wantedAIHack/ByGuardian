import type { Answers, ItemInput, Me, ObservationQuestion, Questionnaire } from './types';

export const EMPTY_ITEM: ItemInput = { level: null, aid: null, consistency: null, hand: null, note: null };

export function needsFullRecheck(me: Me): boolean {
  return me.fullRecheck || me.questionnaireUpgradeRequired === true;
}

export function questionVisible(q: ObservationQuestion, active: Answers): boolean {
  return !q.when || (active[q.when.question] ?? []).some((v) => q.when!.anyOf.includes(v));
}

/** Conditions reference earlier questions; walking in order removes hidden descendants too. */
export function sanitizeAnswers(form: Questionnaire, answers: Answers = {}): Answers {
  const out: Answers = {};
  for (const q of form.questions) {
    if (!questionVisible(q, out)) continue;
    const raw = answers[q.code];
    if (!Array.isArray(raw)) continue;
    const values = [...new Set(raw)].filter((v) => q.options.some((o) => o.code === v));
    if (values.length) out[q.code] = values;
  }
  return out;
}

export function selectAnswer(form: Questionnaire, answers: Answers, question: string, choice: string): Answers {
  const q = form.questions.find((q) => q.code === question);
  if (!q || !q.options.some((o) => o.code === choice)) return sanitizeAnswers(form, answers);
  const current = answers[question] ?? [];
  const exclusive = q.exclusive ?? [];
  const next = q.kind === 'single' ? [choice]
    : current.includes(choice) ? current.filter((v) => v !== choice)
    : exclusive.includes(choice) ? [choice]
    : [...current.filter((v) => !exclusive.includes(v)), choice];
  return sanitizeAnswers(form, { ...answers, [question]: next });
}

export function questionnaireComplete(form: Questionnaire, answers: Answers = {}): boolean {
  const active = sanitizeAnswers(form, answers);
  return form.questions.every((q) => {
    if (!questionVisible(q, active)) return true;
    const values = answers[q.code] ?? [];
    if (!Array.isArray(values)) return false;
    if (!values.length) return !q.required;
    if (q.kind === 'single' && values.length !== 1) return false;
    if (new Set(values).size !== values.length) return false;
    if (values.some((v) => !q.options.some((o) => o.code === v))) return false;
    return values.length === 1 || !values.some((v) => q.exclusive?.includes(v));
  });
}

export function questionnaireInput(form: Questionnaire, value: ItemInput): ItemInput {
  return {
    ...EMPTY_ITEM,
    note: value.note?.trim() || null,
    questionnaireVersion: form.version,
    answers: sanitizeAnswers(form, value.questionnaireVersion === form.version ? value.answers ?? {} : {}),
  };
}
