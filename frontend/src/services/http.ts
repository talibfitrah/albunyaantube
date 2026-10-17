import { useAuthStore } from '@/stores/auth';

// In development, use empty string to go through Vite proxy (avoids CORS)
// In production, use the configured API base URL or fall back to relative URLs
function resolveApiBaseUrl(): string {
  if (import.meta.env.DEV) {
    // Empty = relative, goes through Vite proxy
    return '';
  }

  const configuredUrl = import.meta.env.VITE_API_BASE_URL;
  if (!configuredUrl) {
    console.warn(
      '[http] VITE_API_BASE_URL is not configured in production. ' +
        'Falling back to relative URLs. This may cause issues if the API ' +
        'is hosted on a different origin. Set VITE_API_BASE_URL in your ' +
        'environment or .env.production file.'
    );
    return '';
  }

  return configuredUrl;
}

const API_BASE_URL = resolveApiBaseUrl();

interface ErrorResponseBody {
  message?: string;
  error?: string;
  code?: string;
  details?: Record<string, unknown>;
}

/**
 * Cubic R-final5 P1 — convert an unknown thrown value to a localized
 * message string. Use this in catch blocks instead of
 * {@code err instanceof Error ? err.message : fallback} so backend typed
 * codes get translated through i18n.
 *
 * Usage:
 *   actionError.value = apiErrorToMessage(err, t, 'users.errors.deactivate');
 *
 * If err carries a code (an ApiError, or an axios error whose body has one: F20),
 * the helper looks up i18n key {@code apiErrors.<CODE>} (falling back to the server message).
 * Anything uncoded gets {@code t(fallbackKey)}: its message is English transport text
 * (axios's "Request failed with status code 500", fetch's "Failed to fetch").
 */
export function apiErrorToMessage(
  err: unknown,
  t: (key: string, fallback?: string) => string,
  fallbackKey: string
): string {
  const code = apiErrorCode(err);
  if (code) {
    return t('apiErrors.' + code, apiErrorServerMessage(err) ?? (err instanceof Error ? err.message : code));
  }
  return t(fallbackKey);
}

/** The backend's typed error code, from either transport: fetch (ApiError) or axios (`response.data.code`). */
export function apiErrorCode(err: unknown): string | undefined {
  if (err instanceof ApiError) return err.code;
  const code = (err as { response?: { data?: { code?: unknown } } } | null)?.response?.data?.code;
  return typeof code === 'string' ? code : undefined;
}

function apiErrorServerMessage(err: unknown): string | undefined {
  if (err instanceof ApiError) return err.message;
  const message = (err as { response?: { data?: { message?: unknown } } } | null)?.response?.data?.message;
  return typeof message === 'string' ? message : undefined;
}

/** Spec D9 / RV M7: a whole-request refusal lists each offending row as {ref, code}. */
export function apiErrorOffenders(err: unknown): { ref: string; code: string }[] {
  const details = err instanceof ApiError
    ? err.details
    : (err as { response?: { data?: { details?: Record<string, unknown> } } } | null)?.response?.data?.details;
  const list = (details as { offenders?: unknown } | undefined)?.offenders;
  if (!Array.isArray(list)) return [];
  return list
    .filter((o): o is { ref: string; code: string } => typeof o?.ref === 'string' && typeof o?.code === 'string')
    .map(o => ({ ref: o.ref, code: o.code }));
}

/** The localized message, then one "ref: reason" line per offender. */
export function apiErrorWithOffenders(
  err: unknown,
  t: (key: string, fallback?: string) => string,
  fallbackKey: string
): string {
  const lines = [apiErrorToMessage(err, t, fallbackKey)];
  for (const o of apiErrorOffenders(err)) lines.push(`${o.ref}: ${t('apiErrors.' + o.code, o.code)}`);
  return lines.join('\n');
}

/**
 * Cubic R-final5 P1 — typed error class that preserves the backend's
 * structured error code alongside the human-readable message.
 *
 * <p>Pre-fix, the http layer collapsed responses like
 * {@code {code: "LAST_ADMIN_PROTECTED", message: "..."}} into
 * {@code new Error(message)} — the typed code was thrown away and views
 * could only surface {@code err.message}. Arabic-locale admins saw the
 * English server message verbatim. With ApiError, view code can switch
 * on {@code err.code} and look up an i18n key for localized rendering.
 *
 * <p>Views that still do {@code err instanceof Error ? err.message : fallback}
 * remain correct (ApiError extends Error). Migrating call sites to use
 * {@code err instanceof ApiError ? t('errors.' + err.code, err.message) : ...}
 * is incremental — schedule per-view tickets to bring full i18n coverage.
 */
export class ApiError extends Error {
  readonly code: string | undefined;
  readonly status: number;
  readonly details: Record<string, unknown> | undefined;
  constructor(message: string, status: number, code?: string, details?: Record<string, unknown>) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

export async function authorizedJsonFetch<T>(path: string, init: RequestInit = {}, allowRetry = true): Promise<T> {
  const authStore = useAuthStore();
  const url = `${API_BASE_URL}${path}`;
  const headers = new Headers(init.headers ?? {});
  headers.set('Accept', 'application/json');

  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  if (authStore.idToken) {
    headers.set('Authorization', `Bearer ${authStore.idToken}`);
  }

  const response = await fetch(url, { ...init, headers });

  if (response.status === 401 && allowRetry) {
    const refreshed = await authStore.refreshToken();
    if (refreshed) {
      return authorizedJsonFetch<T>(path, init, false);
    }
  }

  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    let code: string | undefined;
    let details: Record<string, unknown> | undefined;
    try {
      const body = (await response.json()) as ErrorResponseBody;
      if (body.message) {
        message = body.message;
      } else if (body.error) {
        message = body.error;
      }
      code = body.code;
      details = body.details;
    } catch (err) {
      console.warn('Failed to parse error response', err);
    }
    // Cubic R-final5 P1 — throw ApiError instead of plain Error so views
    // can extract a typed code for i18n. ApiError extends Error so
    // existing `err instanceof Error` / `err.message` consumers keep
    // working unchanged.
    throw new ApiError(message, response.status, code, details);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
