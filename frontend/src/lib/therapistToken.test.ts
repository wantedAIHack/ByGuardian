import { describe, expect, it } from 'vitest';
import {
  captureTherapistToken,
  clearTherapistToken,
  therapistShareUrl,
} from './therapistToken';

const TOKEN = '123e4567-e89b-12d3-a456-426614174000';
const STORAGE_KEY = 'nextvisit.therapist-token';

describe('therapist token URL boundary', () => {
  it('builds a same-origin share URL with the token in the fragment', () => {
    expect(therapistShareUrl(TOKEN)).toBe(`${window.location.origin}/t#${TOKEN}`);
  });

  it('captures a valid fragment in tab storage and scrubs it from the URL', () => {
    window.history.replaceState(null, '', `/t#${TOKEN}`);

    expect(captureTherapistToken()).toBe(TOKEN);
    expect(window.location.pathname + window.location.hash).toBe('/t');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBe(TOKEN);
  });

  it('preserves the search while scrubbing the fragment', () => {
    window.history.replaceState(null, '', `/t?print=1#${TOKEN}`);

    expect(captureTherapistToken()).toBe(TOKEN);
    expect(window.location.pathname + window.location.search + window.location.hash)
      .toBe('/t?print=1');
  });

  it('rejects a present invalid fragment and clears an older stored token', () => {
    sessionStorage.setItem(STORAGE_KEY, TOKEN);
    window.history.replaceState(null, '', '/t#not-a-uuid');

    expect(captureTherapistToken()).toBeNull();
    expect(window.location.hash).toBe('');
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('reuses only a valid token stored in the current tab when no fragment is present', () => {
    sessionStorage.setItem(STORAGE_KEY, TOKEN);
    localStorage.setItem(STORAGE_KEY, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    window.history.replaceState(null, '', '/t');

    expect(captureTherapistToken()).toBe(TOKEN);
  });

  it('discards an invalid stored value when no fragment is present', () => {
    sessionStorage.setItem(STORAGE_KEY, 'NOT-A-UUID');
    window.history.replaceState(null, '', '/t');

    expect(captureTherapistToken()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('clears the captured token', () => {
    sessionStorage.setItem(STORAGE_KEY, TOKEN);

    clearTherapistToken();

    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('does not recover another tab token from local storage', () => {
    localStorage.setItem(STORAGE_KEY, TOKEN);
    window.history.replaceState(null, '', '/t');

    expect(captureTherapistToken()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('replaces the previous token with a newly shared fragment', () => {
    sessionStorage.setItem(STORAGE_KEY, '223e4567-e89b-12d3-a456-426614174001');
    window.history.replaceState({ idx: 2 }, '', `/t#${TOKEN}`);

    expect(captureTherapistToken()).toBe(TOKEN);
    expect(sessionStorage.getItem(STORAGE_KEY)).toBe(TOKEN);
    expect(window.history.state).toEqual({ idx: 2 });
    expect(window.location.hash).toBe('');
    // A repeated initializer or reload uses the current tab's captured token.
    expect(captureTherapistToken()).toBe(TOKEN);
  });

  it.each([TOKEN.toUpperCase(), `${TOKEN}/extra`, `${TOKEN}?x=1`, `%31${TOKEN.slice(1)}`])(
    'scrubs and rejects a fragment outside the backend UUID shape: %s', (invalid) => {
      sessionStorage.setItem(STORAGE_KEY, TOKEN);
      window.history.replaceState(null, '', `/t#${invalid}`);

      expect(captureTherapistToken()).toBeNull();
      expect(window.location.hash).toBe('');
      expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
      expect(() => therapistShareUrl(invalid)).toThrow();
    },
  );

  it('refuses to construct a share URL for an invalid token', () => {
    expect(() => therapistShareUrl('not-a-uuid')).toThrow();
  });

  it('treats an explicitly empty fragment as invalid and clears an older token', () => {
    sessionStorage.setItem(STORAGE_KEY, TOKEN);
    window.history.replaceState(null, '', '/t#');

    expect(captureTherapistToken()).toBeNull();
    expect(window.location.href).toBe(`${window.location.origin}/t`);
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('refuses a share token with a trailing newline', () => {
    expect(() => therapistShareUrl(`${TOKEN}\n`)).toThrow();
  });
});
