const STORAGE_KEY = 'gatekeeper.session';

// { token, expiresAt, user } kept in localStorage so a page refresh keeps you signed in
export function loadSession() {
  try {
    const session = JSON.parse(localStorage.getItem(STORAGE_KEY));
    if (!session || session.expiresAt <= Date.now()) {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    // storage unavailable (private mode); the session still lives in memory
  }
}

export function clearSession() {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

export function getToken() {
  return loadSession()?.token ?? null;
}
