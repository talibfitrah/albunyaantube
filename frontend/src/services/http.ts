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
 * If err is an ApiError with a code, the helper looks up i18n key
 * {@code apiErrors.<CODE>} (falling back to {@code err.message}).
 * Anything else falls back to {@code t(fallbackKey)}.
 */
export function apiErrorToMessage(
  err: unknown,
  t: (key: string, fallback?: string) => string,
  fallbackKey: string
): string {
  if (err instanceof ApiError && err.code) {
    return t('apiErrors.' + err.code, err.message);
  }
  if (err instanceof Error) return err.message;
  return t(fallbackKey);
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
  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
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
    try {
      const body = (await response.json()) as ErrorResponseBody;
      if (body.message) {
        message = body.message;
      } else if (body.error) {
        message = body.error;
      }
      code = body.code;
    } catch (err) {
      console.warn('Failed to parse error response', err);
    }
    // Cubic R-final5 P1 — throw ApiError instead of plain Error so views
    // can extract a typed code for i18n. ApiError extends Error so
    // existing `err instanceof Error` / `err.message` consumers keep
    // working unchanged.
    throw new ApiError(message, response.status, code);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
