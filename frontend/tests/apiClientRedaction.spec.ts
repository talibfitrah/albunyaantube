import { describe, it, expect, vi } from 'vitest';
import { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { apiClient } from '@/services/api/client';

const auth = { idToken: 'tok-1', refreshToken: vi.fn(), logout: vi.fn() };
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/utils/toast', () => ({ toast: { error: vi.fn() } }));

const fail = (config: InternalAxiosRequestConfig, status: number) =>
  Promise.reject(new AxiosError('x', 'ERR', config, {}, { status, data: {}, statusText: '', headers: {}, config }));

describe('API client log redaction', () => {
  it('a rejected request carries no Authorization header', async () => {
    apiClient.defaults.adapter = c => fail(c, 500);
    const e: AxiosError = await apiClient.get('/x').catch(err => err);
    expect(e.config?.headers.Authorization).toBeUndefined();
  });

  it('a 401 refreshes and the retry still sends the token', async () => {
    const sent: unknown[] = [];
    apiClient.defaults.adapter = c => {
      sent.push(c.headers.Authorization);
      return sent.length === 1
        ? fail(c, 401)
        : Promise.resolve({ data: 'ok', status: 200, statusText: '', headers: {}, config: c });
    };
    auth.refreshToken.mockImplementation(async () => { auth.idToken = 'tok-2'; return true; });
    const r = await apiClient.get('/x');
    expect(r.data).toBe('ok');
    expect(sent).toEqual(['Bearer tok-1', 'Bearer tok-2']);
  });
});
