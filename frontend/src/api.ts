import type { Actor, BidOutcome, Intent } from './types';

interface ErrorBody { code?: string; message?: string }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

async function csrf(): Promise<Record<string, string>> {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new ApiError(response.status, 'CSRF_UNAVAILABLE', 'The secure session could not be prepared. Please try again.');
  const value: { token: string; headerName: string } = await response.json();
  if (!value.token || !value.headerName) throw new Error('The secure session response was incomplete.');
  return { [value.headerName]: value.token };
}

let refreshFlight: Promise<Actor> | null = null;

function actorChanged(): ApiError {
  return new ApiError(409, 'ACTOR_CHANGED',
    'Sign in to the account that created this bid before checking or resending it. Your original bid has been kept.');
}

function recoverSession(): Promise<Actor> {
  if (refreshFlight) return refreshFlight;
  const locks = globalThis.navigator?.locks;
  if (!locks) {
    // Without origin-wide coordination, concurrent tabs could reuse a rotated token.
    return Promise.reject(new ApiError(401, 'SIGN_IN_REQUIRED', 'Your session expired. Please sign in again.'));
  }
  refreshFlight = locks.request('auctionhouse.auth.refresh', { mode: 'exclusive' }, async () => {
    try {
      // Another tab may have rotated the shared HttpOnly cookie while this tab waited.
      return await request<Actor>('/api/auth/session', {}, false);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
    }
    await request<void>('/api/auth/refresh', { method: 'POST' }, false);
    return request<Actor>('/api/auth/session', {}, false);
  }).finally(() => { refreshFlight = null; });
  return refreshFlight;
}

export async function request<T>(path: string, options: RequestInit = {}, retrySession = true): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase();
  const secureHeaders = ['GET', 'HEAD', 'OPTIONS'].includes(method) ? {} : await csrf();
  const headers = new Headers(options.headers);
  if (options.body) headers.set('Content-Type', 'application/json');
  Object.entries(secureHeaders).forEach(([name, value]) => headers.set(name, value));
  const response = await fetch(path, { ...options, method, headers, credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 401 && retrySession && !path.startsWith('/api/auth/')) {
    const actor = await recoverSession();
    const expectedActor = headers.get('X-Expected-Actor');
    if (expectedActor && expectedActor !== actor.id) throw actorChanged();
    return request<T>(path, options, false);
  }
  if (response.status === 204) return undefined as T;
  const data: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 422 && data && typeof data === 'object' && 'accepted' in data) return data as T;
    const error = (data ?? {}) as ErrorBody;
    if (response.status === 409 && error.code === 'ACTOR_CHANGED') throw actorChanged();
    throw new ApiError(response.status, error.code ?? 'REQUEST_FAILED',
      error.message ?? (response.status === 401 ? 'Please sign in to continue.' : 'The request could not be completed.'));
  }
  return data as T;
}

export const post = <T>(path: string, body?: unknown, headers?: HeadersInit) =>
  request<T>(path, { method: 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body) });

export async function getSession(): Promise<Actor | null> {
  try { return await request<Actor>('/api/auth/session'); }
  catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
    try {
      return await recoverSession();
    } catch (refreshError) {
      if (refreshError instanceof ApiError && [400, 401, 403].includes(refreshError.status)) return null;
      throw refreshError;
    }
  }
}

export async function findOutcome(intent: Intent): Promise<BidOutcome | null> {
  try { return await request<BidOutcome>(`/api/auctions/${intent.auctionId}/bid-intents/${encodeURIComponent(intent.key)}`,
    { headers: { 'X-Expected-Actor': intent.actorId } }); }
  catch (error) {
    if (error instanceof ApiError && error.status === 404 && error.code === 'INTENT_UNKNOWN') return null;
    throw error;
  }
}

export async function reconcileOrSend(intent: Intent, checkFirst: boolean): Promise<BidOutcome> {
  if (checkFirst) {
    const known = await findOutcome(intent);
    if (known) return known;
  }
  try {
    return await post<BidOutcome>(`/api/auctions/${intent.auctionId}/bids`,
      { amountMinor: intent.amountMinor }, { 'Idempotency-Key': intent.key, 'X-Expected-Actor': intent.actorId });
  } catch (error) {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429) throw error;
    // A transport failure can follow commit. Resolve the same identity first.
    const known = await findOutcome(intent).catch(() => null);
    if (known) return known;
    throw new Error('Your bid result is not yet known. Check again using the same bid; do not submit another.');
  }
}
