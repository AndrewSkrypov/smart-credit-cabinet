import { UserSession } from '../types';

const KEY = 'cabinet.session';

/** The stored session, or null when there is none or it cannot be read. */
export function readSession(): UserSession | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as UserSession;
    return session?.token && session?.user ? session : null;
  } catch {
    return null;
  }
}

export function writeSession(session: UserSession) {
  try {
    localStorage.setItem(KEY, JSON.stringify(session));
  } catch {
    // private mode or full storage: the session lives until the tab is closed
  }
}

export function clearSession() {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // nothing stored
  }
}
