import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, onUnauthorized } from '../api/client.js';
import { clearSession, loadSession, saveSession } from './session.js';

// Platform admins see every organization, tenant admins only their own
const ROLE_AREAS = {
  PLATFORM_ADMIN: { home: '/dashboard', prefix: '/', label: 'Platform admin' },
  TENANT_ADMIN: { home: '/dashboard', prefix: '/', label: 'Tenant admin' },
};

export function homeFor(user) {
  return ROLE_AREAS[user?.role]?.home ?? '/login';
}

export function canVisit(user, path) {
  const prefix = ROLE_AREAS[user?.role]?.prefix;
  return Boolean(prefix && path?.startsWith(prefix));
}

export function roleLabel(role) {
  return ROLE_AREAS[role]?.label ?? role;
}

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => loadSession());
  const [notice, setNotice] = useState(null);
  const navigate = useNavigate();

  const logout = useCallback((message = null) => {
    clearSession();
    setSession(null);
    setNotice(message);
    navigate('/login', { replace: true });
  }, [navigate]);

  // Token rejected by the backend: expired, user deleted or organization suspended
  useEffect(() => {
    onUnauthorized(() => logout('Your session has ended. Please sign in again.'));
  }, [logout]);

  // Sign out exactly when the JWT expires instead of waiting for the next failing request
  useEffect(() => {
    if (!session) return undefined;
    const timer = setTimeout(() => logout('Your session expired. Please sign in again.'),
      Math.max(session.expiresAt - Date.now(), 0));
    return () => clearTimeout(timer);
  }, [session, logout]);

  const login = useCallback(async (username, password) => {
    const auth = await api('/auth/login', { method: 'POST', body: { username, password }, token: null });
    const user = await api('/auth/me', { token: auth.accessToken });
    const next = { token: auth.accessToken, expiresAt: Date.now() + auth.expiresIn * 1000, user };
    saveSession(next);
    setSession(next);
    setNotice(null);
    return user;
  }, []);

  const value = useMemo(() => ({
    user: session?.user ?? null,
    expiresAt: session?.expiresAt ?? null,
    notice,
    login,
    logout,
  }), [session, notice, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
