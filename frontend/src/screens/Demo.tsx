import { PageHeader } from '../ui/PageHeader';
import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { ApiError, api, setToken } from '../lib/api';
import { therapistShareUrl } from '../lib/therapistToken';
import type { DemoResponse } from '../lib/types';
import { Button } from '../ui/Button';
import { Notice } from '../ui/Notice';


/** 심사위원이 온보딩 없이 다섯 화면을 다 보게 하는 입구. */
export function Demo() {
  const [error, setError] = useState<string | null>(null);
  const create = useMutation({
    mutationFn: () => api.post<DemoResponse>('/demo'),
    onSuccess: (res) => { setToken(res.guardianToken); setError(null); },
    onError: (e) => setError(e instanceof ApiError ? e.message : '만들지 못했습니다.'),
  });

  if (create.data) {
    return (
      <main className="app-page"><div className="note-surface">
        <PageHeader title="데모 기록을 만들었습니다." focusKey="demo-created" />
        <p className="pt-4">여섯 주치 관찰이 들어 있습니다.</p>
        <div className="flex flex-col gap-3 pt-8">
          <Link className="btn" to="/">보호자 화면 보기</Link>
          <a className="btn btn-plain" href={therapistShareUrl(create.data.therapistToken)}>치료사 화면 보기</a>
        </div>
        <Notice>이어받기 코드 — {create.data.recoveryCode}</Notice>
      </div></main>
    );
  }

  return (
    <main className="app-page"><div className="note-surface">
      <PageHeader title="데모" backTo="/" focusKey="demo" />
      <p className="pt-4">여섯 주치 관찰이 들어 있는 기록을 하나 만들어 드립니다.</p>
      <div className="flex flex-col gap-3 pt-8">
        {error ? <p role="alert">{error}</p> : null}
        <Button disabled={create.isPending} onClick={() => create.mutate()}>
          {create.isPending ? '만드는 중입니다…' : '데모 기록 만들기'}
        </Button>
      </div>
    </div></main>
  );
}
