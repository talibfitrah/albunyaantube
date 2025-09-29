import { defineStore } from 'pinia';
import { computed, reactive, ref } from 'vue';

interface LoginPayload {
  email: string;
  password: string;
}

interface TokenResponse {
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  roles: string[];
}

interface StoredSession {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresAt: string;
  refreshTokenExpiresAt: string;
  email: string;
  roles: string[];
}

const STORAGE_KEY = 'albunyaan-admin-session';
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

function createEmptySession(): StoredSession {
  return {
    accessToken: '',
    refreshToken: '',
    accessTokenExpiresAt: '',
    refreshTokenExpiresAt: '',
    email: '',
    roles: []
  };
}

export const useAuthStore = defineStore('auth', () => {
  const session = reactive<StoredSession>(createEmptySession());
  const isLoading = ref(false);
  const error = ref<string | null>(null);

  function initializeFromStorage() {
    const cached = localStorage.getItem(STORAGE_KEY);
    if (!cached) {
      return;
    }
    try {
      const parsed = JSON.parse(cached) as StoredSession;
      Object.assign(session, parsed);
    } catch (err) {
      console.warn('Failed to parse cached session', err);
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  function persistSession(next: StoredSession | null) {
    if (!next) {
      localStorage.removeItem(STORAGE_KEY);
      Object.assign(session, createEmptySession());
      return;
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    Object.assign(session, next);
  }

  const isAuthenticated = computed(() => Boolean(session.accessToken && session.refreshToken));
  const bearerToken = computed(() => (session.accessToken ? `Bearer ${session.accessToken}` : null));

  async function login(payload: LoginPayload): Promise<boolean> {
    isLoading.value = true;
    error.value = null;
    try {
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        if (response.status === 401) {
          error.value = 'Invalid email or password.';
        } else {
          error.value = 'Unable to sign in. Please try again.';
        }
        return false;
      }

      const data = (await response.json()) as TokenResponse;
      const nextSession: StoredSession = {
        accessToken: data.accessToken,
        refreshToken: data.refreshToken,
        accessTokenExpiresAt: data.accessTokenExpiresAt,
        refreshTokenExpiresAt: data.refreshTokenExpiresAt,
        email: payload.email,
        roles: data.roles
      };
      persistSession(nextSession);
      return true;
    } catch (err) {
      console.error('Login failed', err);
      error.value = 'A network error prevented sign-in.';
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  async function refresh(): Promise<boolean> {
    if (!session.refreshToken) {
      return false;
    }
    try {
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refreshToken })
      });
      if (!response.ok) {
        clearSession();
        return false;
      }
      const data = (await response.json()) as TokenResponse;
      persistSession({
        accessToken: data.accessToken,
        refreshToken: data.refreshToken,
        accessTokenExpiresAt: data.accessTokenExpiresAt,
        refreshTokenExpiresAt: data.refreshTokenExpiresAt,
        email: session.email,
        roles: data.roles
      });
      return true;
    } catch (err) {
      console.error('Refresh failed', err);
      clearSession();
      return false;
    }
  }

  async function logout(): Promise<void> {
    if (!session.refreshToken) {
      clearSession();
      return;
    }

    try {
      await fetch(`${API_BASE_URL}/api/v1/auth/logout`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: session.accessToken ? `Bearer ${session.accessToken}` : ''
        },
        body: JSON.stringify({ refreshToken: session.refreshToken })
      });
    } catch (err) {
      console.warn('Logout request failed', err);
    } finally {
      clearSession();
    }
  }

  function clearSession() {
    persistSession(null);
  }

  return {
    session,
    isLoading,
    error,
    isAuthenticated,
    bearerToken,
    initializeFromStorage,
    login,
    logout,
    refresh
  };
});
