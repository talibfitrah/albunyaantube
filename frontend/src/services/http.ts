import { useAuthStore } from '@/stores/auth';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

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
