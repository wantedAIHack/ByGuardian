import { createContext, useContext, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from './api';
import type { Catalog, CodeLabel } from './types';

// --- 순수 조회 함수. React 없이 테스트한다 ---

export function itemByCode(c: Catalog, code: string) {
  return c.items.find((i) => i.code === code);
}

export function axisValues(c: Catalog, axis: string) {
  return c.axes[axis] ?? [];
}

export function axisValueLabel(c: Catalog, axis: string, value: number): string {
  return axisValues(c, axis).find((v) => v.value === value)?.label ?? '';
}

export function axisName(c: Catalog, axis: string): string {
  return codeLabel(c.axisLabels, axis);
}

/**
 * 선택지 위 h3 헤더용. 인라인 라벨(axisName)과 다른 문자열인 이유는 자리가 다르기
 * 때문이다. "문턱·계단 · 이번 주 빈도"에는 명사구가 맞고, 선택지 위에 홀로 서는
 * 헤더에는 질문이 맞다. 서버가 질문을 안 준 축은 라벨로 폴백한다.
 */
export function axisQuestion(c: Catalog | undefined, axis: string): string {
  if (!c) return '';
  const q = c.axisQuestions?.find((x) => x.code === axis);
  return q ? q.label : axisName(c, axis);
}

export function codeLabel(list: CodeLabel[], code: string): string {
  return list.find((x) => x.code === code)?.label ?? '';
}

// --- React 배선 ---

const Ctx = createContext<Catalog | null>(null);

/** 앱 시작 시 한 번 받고 오래 캐시한다. 카탈로그는 배포 사이에 바뀌지 않는다. */
export function CatalogProvider({ children }: { children: ReactNode }) {
  const { data, isPending, isError } = useQuery({
    queryKey: ['catalog'],
    queryFn: () => api.get<Catalog>('/catalog'),
    staleTime: Infinity,
    gcTime: Infinity,
  });

  if (isPending) return <p className="p-gutter text-ink-soft">불러오는 중입니다…</p>;
  if (isError || !data) {
    return (
      <div className="p-gutter">
        <p>연결이 되지 않습니다. 잠시 후 다시 열어주세요.</p>
      </div>
    );
  }
  return <Ctx.Provider value={data}>{children}</Ctx.Provider>;
}

export function useCatalog(): Catalog {
  const c = useContext(Ctx);
  if (!c) throw new Error('CatalogProvider 안에서만 쓸 수 있습니다');
  return c;
}
