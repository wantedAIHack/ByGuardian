import { useEffect, useState } from 'react';
import {
  DIAGNOSIS_CHOICES, PARETIC_SIDE_CHOICES, RELATIONS, VERBAL_DIFFICULTY_CHOICES,
} from '../lib/constants';
import { ONBOARDING_DRAFT, loadDraft, saveDraft } from '../lib/draft';
import {
  LAST_STEP, canAdvance, initialState, type OnboardingState,
} from '../lib/onboarding';
import type { Catalog } from '../lib/types';
import { Button } from '../ui/Button';
import { Choice } from '../ui/Choice';
import { Notice } from '../ui/Notice';
import { Screen } from '../ui/Screen';

export function Onboarding({ catalog }: { catalog: Catalog }) {
  const [s, setS] = useState<OnboardingState>(
    () => loadDraft<OnboardingState>(ONBOARDING_DRAFT) ?? initialState(),
  );

  // 매 변화마다 남긴다. 15단계(복구 코드) 이후로는 케이스가 이미 만들어져 초안이 무의미하다.
  useEffect(() => {
    if (s.step < 15) saveDraft(ONBOARDING_DRAFT, s);
  }, [s]);

  const set = (patch: Partial<OnboardingState>) => setS((prev) => ({ ...prev, ...patch }));
  const go = (delta: number) => setS((prev) => ({ ...prev, step: prev.step + delta }));

  const nextButton = (
    <Button disabled={!canAdvance(catalog, s)} onClick={() => go(1)}>다음</Button>
  );

  if (s.step === 0) {
    return (
      <Screen>
        <h1 className="text-title font-semibold">잠깐만 여쭤보겠습니다</h1>
        <p className="pt-4">5분 정도 걸립니다. 8가지를 여쭤봅니다.</p>
        <p className="pt-2 text-ink-soft">
          한 번만 하시면 됩니다. 그 뒤로는 매주 3분이면 충분합니다.
        </p>
        <div className="pt-8">
          <Button onClick={() => go(1)}>시작</Button>
        </div>
      </Screen>
    );
  }

  const common = { step: s.step, total: LAST_STEP, onBack: () => go(-1) };

  if (s.step === 1) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">어떤 분이신가요?</h2>
        <div className="flex flex-col gap-3 pt-6">
          {RELATIONS.map((r) => (
            <Choice
              key={r}
              label={r}
              selected={s.relation === r}
              onSelect={() => set({ relation: r })}
            />
          ))}
        </div>
        {s.relation === '기타' ? (
          <label className="block pt-4">
            <span className="text-small text-ink-soft">어떤 관계이신가요?</span>
            <input
              className="mt-2 min-h-[56px] w-full rounded-lg border border-line px-4 text-btn"
              value={s.relationOther}
              onChange={(e) => set({ relationOther: e.target.value })}
            />
          </label>
        ) : null}
      </Screen>
    );
  }

  if (s.step === 2) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">어떤 진단을 받으셨나요?</h2>
        <Notice>모르셔도 괜찮습니다. 나중에 바꿀 수 있습니다.</Notice>
        <div className="flex flex-col gap-3 pt-6">
          {DIAGNOSIS_CHOICES.map((c) => (
            <Choice
              key={c.value}
              label={c.label}
              selected={s.diagnosis === c.value}
              onSelect={() => set({ diagnosis: c.value })}
            />
          ))}
        </div>
      </Screen>
    );
  }

  if (s.step === 3) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">마비되신 쪽이 어디인가요?</h2>
        <div className="flex flex-col gap-3 pt-6">
          {PARETIC_SIDE_CHOICES.map((c) => (
            <Choice
              key={c.value}
              label={c.label}
              selected={s.pareticSide === c.value}
              onSelect={() => set({ pareticSide: c.value })}
            />
          ))}
        </div>
      </Screen>
    );
  }

  if (s.step === 4) {
    return (
      <Screen {...common} footer={nextButton}>
        <h2 className="text-title font-semibold">말씀으로 불편한 곳을 알려주시나요?</h2>
        <div className="flex flex-col gap-3 pt-6">
          {VERBAL_DIFFICULTY_CHOICES.map((c) => (
            <Choice
              key={c.value}
              label={c.label}
              selected={s.verbalDifficulty === c.value}
              onSelect={() => set({ verbalDifficulty: c.value })}
            />
          ))}
        </div>
      </Screen>
    );
  }

  if (s.step === 5) {
    return (
      <Screen
        {...common}
        footer={
          <div className="flex flex-col gap-3">
            <Button disabled={!s.nextVisitDate} onClick={() => go(1)}>다음</Button>
            <Button variant="quiet" onClick={() => set({ nextVisitDate: null, step: s.step + 1 })}>
              건너뛰기
            </Button>
          </div>
        }
      >
        <h2 className="text-title font-semibold">다음 진료일이 정해져 있나요?</h2>
        <Notice>모르시면 건너뛰셔도 됩니다. 나중에 설정에서 넣으실 수 있습니다.</Notice>
        <input
          type="date"
          className="mt-6 min-h-[56px] w-full rounded-lg border border-line px-4 text-btn"
          value={s.nextVisitDate ?? ''}
          onChange={(e) => set({ nextVisitDate: e.target.value || null })}
        />
      </Screen>
    );
  }

  if (s.step === 6) {
    return (
      <Screen {...common} footer={<Button onClick={() => go(1)}>시작</Button>}>
        <h2 className="text-title font-semibold">지금 상태를 8가지로 한 번 여쭤보겠습니다.</h2>
        <p className="pt-4 text-ink-soft">
          지금 어떠신지가 기준이 됩니다. 정답이 없으니 보이시는 대로 고르시면 됩니다.
        </p>
      </Screen>
    );
  }

  return (
    <Screen {...common}>
      <p>기준선 — 준비 중</p>
    </Screen>
  );
}
