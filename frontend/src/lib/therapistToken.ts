const STORAGE_KEY = 'nextvisit.therapist-token';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function isUuid(value: string): boolean {
  return UUID.test(value);
}

export function captureTherapistToken(): string | null {
  if (window.location.href.includes('#')) {
    const token = window.location.hash.slice(1);
    window.history.replaceState(
      window.history.state,
      '',
      window.location.pathname + window.location.search,
    );

    if (isUuid(token)) {
      sessionStorage.setItem(STORAGE_KEY, token);
      return token;
    }

    sessionStorage.removeItem(STORAGE_KEY);
    return null;
  }

  const stored = sessionStorage.getItem(STORAGE_KEY);
  if (stored !== null && isUuid(stored)) return stored;

  sessionStorage.removeItem(STORAGE_KEY);
  return null;
}

export function clearTherapistToken(): void {
  sessionStorage.removeItem(STORAGE_KEY);
}

export function therapistShareUrl(token: string): string {
  if (!isUuid(token)) throw new Error('Invalid therapist token');
  return `${window.location.origin}/t#${token}`;
}
