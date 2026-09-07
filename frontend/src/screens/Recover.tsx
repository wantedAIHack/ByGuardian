import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { ApiError, api, setToken } from '../lib/api';
import { RELATIONS } from '../lib/constants';
import type { RecoverResponse } from '../lib/types';
import { Button } from '../ui/Button';
import { Choice } from '../ui/Choice';
import { Notice } from '../ui/Notice';
import { Screen } from '../ui/Screen';

export function Recover() {
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [relation, setRelation] = useState<string | null>(null);
  const [other, setOther] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const resolved = relation === '기타' ? other.trim() : (relation ?? '');

  const recover = useMutation({
    mutationFn: () =>
      api.post<RecoverResponse>('/guardians/recover', {
        recoveryCode: code.trim().toUpperCase(),
        relation: resolved,
      }),
    onSuccess: (res) => {
      setToken(res.guardianToken);
      setDone(true);
    },
    onError: (e) => {
      setError(e instanceof ApiError && e.status === 404
        ? '코드를 다시 확인해 주세요.'
        : e instanceof ApiError ? e.message : '이어받지 못했습니다.');
    },
  });

  if (done) {
    return (
      <Screen footer={<Button onClick={() => navigate('/', { replace: true })}>홈으로</Button>}>
        <p className="text-title font-semibold">기록을 되찾았습니다.</p>
      </Screen>
    );
  }

  return (
    <Screen
      footer={
        <div className="flex flex-col gap-3">
          {error ? <p>{error}</p> : null}
          <Button
            disabled={code.trim().length === 0 || resolved.length === 0 || recover.isPending}
            onClick={() => { setError(null); recover.mutate(); }}
          >
            {recover.isPending ? '찾는 중입니다…' : '이어받기'}
          </Button>
          <p className="text-center text-small">
            <Link className="inline-flex min-h-[48px] items-center text-ink-soft underline" to="/">
              ← 홈
            </Link>
          </p>
        </div>
      }
    >
      <h1 className="text-title font-semibold">이어받기</h1>
      <label className="block pt-8">
        <span className="text-small text-ink-soft">이어받기 코드</span>
        <input
          aria-label="이어받기 코드"
          className="mt-2 min-h-[56px] w-full rounded-lg border border-line px-4 text-[24px] tracking-[0.15em] uppercase"
          value={code}
          maxLength={8}
          autoCapitalize="characters"
          onChange={(e) => setCode(e.target.value)}
        />
      </label>

      {/* 관계를 함께 받는다. 이 답이 그대로 작성자 라벨이 되어 치료사용 요약의 작성자 변경 표시에 쓰인다. */}
      <div className="pt-10">
        <h2 className="font-semibold">어떤 분이신가요?</h2>
        <div className="flex flex-col gap-3 pt-4">
          {RELATIONS.map((r) => (
            <Choice key={r} label={r} selected={relation === r} onSelect={() => setRelation(r)} />
          ))}
        </div>
        {relation === '기타' ? (
          <label className="block pt-4">
            <span className="text-small text-ink-soft">어떤 관계이신가요?</span>
            <input
              className="mt-2 min-h-[56px] w-full rounded-lg border border-line px-4"
              value={other}
              onChange={(e) => setOther(e.target.value)}
            />
          </label>
        ) : null}
        <Notice>누가 남긴 기록인지 치료사용 요약에 함께 나갑니다.</Notice>
      </div>
    </Screen>
  );
}
