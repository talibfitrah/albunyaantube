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
    try {
      const body = (await response.json()) as ErrorResponseBody;
      if (body.message) {
        message = body.message;
      } else if (body.error) {
        message = body.error;
      }
    } catch (err) {
      console.warn('Failed to parse error response', err);
    }
    throw new Error(message);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
