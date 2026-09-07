import { describe, expect, it } from 'vitest';
import { axisName, axisValueLabel, axisValues, codeLabel, itemByCode } from './catalog';
import { catalogFixture } from '../test/fixtures';

const c = catalogFixture;

describe('catalog', () => {
  it('코드로 항목을 찾는다', () => {
    expect(itemByCode(c, 'transfer')?.label).toBe('침대·의자에서 옮겨 앉기');
    expect(itemByCode(c, 'nope')).toBeUndefined();
  });

  it('축의 값 목록을 준다', () => {
    expect(axisValues(c, 'LEVEL')).toHaveLength(4);
    expect(axisValues(c, 'HAND')).toHaveLength(3);
    expect(axisValues(c, 'NOPE')).toEqual([]);
  });

  it('축 값의 라벨을 준다', () => {
    expect(axisValueLabel(c, 'LEVEL', 3)).toBe('혼자 하심');
    expect(axisValueLabel(c, 'LEVEL', 9)).toBe('');
  });

  it('축 이름을 카탈로그에서 읽는다', () => {
    expect(axisName(c, 'LEVEL')).toBe('도움 수준');
    expect(axisName(c, 'CONSISTENCY')).toBe('한 주 일관성');
    expect(axisName(c, 'NOPE')).toBe('');
  });

  it('code-label 목록에서 라벨을 찾는다', () => {
    expect(codeLabel(c.sleepLevels, '2')).toBe('잘 주무심');
    expect(codeLabel(c.sleepLevels, '9')).toBe('');
  });
});
