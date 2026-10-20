import { getToken } from '../auth/session.js';
import { mockResponse } from './mocks.js';

// Login lives in api-management-service; everything else in this app's own backend
const AUTH_URL = import.meta.env.VITE_AUTH_API_URL ?? 'http://localhost:8081';
const SERVICE_URL = import.meta.env.VITE_SERVICE_API_URL ?? 'http://localhost:8082';

// Until the backend exists, non-auth calls are answered by mocks.js (set VITE_USE_MOCK_DATA=false to go live)
export const USE_MOCK_DATA = import.meta.env.VITE_USE_MOCK_DATA !== 'false';

let unauthorizedHandler = () => {};

// AuthContext registers this so any 401 on an authenticated call logs the user out
export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

export class ApiError extends Error {
  constructor(status, message, fieldErrors = {}) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

/**
 * Sends the stored JWT unless `token` is given explicitly (or `token: null` for public calls)
 * and turns ProblemDetail error bodies into ApiError.
 */
export async function api(path, { method = 'GET', body, token = getToken() } = {}) {
  const isAuthCall = path.startsWith('/auth');
  if (USE_MOCK_DATA && !isAuthCall) {
    return mockResponse(path, { method, body });
  }

  const baseUrl = isAuthCall ? AUTH_URL : SERVICE_URL;
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;

  let response;
  try {
    response = await fetch(baseUrl + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Cannot reach ' + baseUrl + '. Is the service running?');
  }

  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);

  if (!response.ok) {
    if (response.status === 401 && token) unauthorizedHandler();
    const message = data?.detail
      ?? (response.status === 401 ? 'Your session has ended. Please sign in again.' : `Request failed (${response.status})`);
    throw new ApiError(response.status, message, data?.errors ?? {});
  }
  return data;
}
