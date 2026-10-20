import { getToken } from '../auth/session.js';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8081';
export const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL ?? 'http://localhost:8080';

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
 * Reads one of the gateway's own public endpoints (/_gateway/...) straight from the browser; api-management never
 * calls the gateway. Resolves to { status, data } also for 503 (not ready, the body says why), or null when the
 * gateway can't be reached. Behind a load balancer this is whichever instance answers.
 */
export async function gatewayApi(path) {
  try {
    const response = await fetch(GATEWAY_URL + path);
    return { status: response.status, data: await response.json().catch(() => null) };
  } catch {
    return null;
  }
}

/**
 * Calls the API management backend. Sends the stored JWT unless `token` is given explicitly
 * (or `token: null` for public calls) and turns ProblemDetail error bodies into ApiError.
 */
export async function api(path, { method = 'GET', body, token = getToken() } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;

  let response;
  try {
    response = await fetch(BASE_URL + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the API server. Is the backend running on ' + BASE_URL + '?');
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
