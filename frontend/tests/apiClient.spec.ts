import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { apiClient } from '@/services/api/client';
import { useAuthStore } from '@/stores/auth';
import type { InternalAxiosRequestConfig } from 'axios';

// Mock only the auth store and toast, NOT axios
vi.mock('@/stores/auth', () => ({
  useAuthStore: vi.fn(() => ({
    idToken: null,
    refreshToken: vi.fn(),
    logout: vi.fn()
  }))
}));
vi.mock('@/utils/toast', () => ({
  toast: {
    error: vi.fn()
  }
}));

describe('API Client', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should be an axios instance', () => {
    expect(apiClient).toBeDefined();
    expect(typeof apiClient.get).toBe('function');
    expect(typeof apiClient.post).toBe('function');
    expect(typeof apiClient.put).toBe('function');
    expect(typeof apiClient.delete).toBe('function');
  });

  it('should have correct base URL', () => {
    expect(apiClient.defaults.baseURL).toBeDefined();
    // Should be either env var or default
    expect(apiClient.defaults.baseURL).toMatch(/^https?:\/\//);
  });

  it('should have correct default headers', () => {
    expect(apiClient.defaults.headers['Content-Type']).toBe('application/json');
    expect(apiClient.defaults.headers['Accept']).toBe('application/json');
  });

  it('should have 30 second timeout', () => {
    expect(apiClient.defaults.timeout).toBe(30000);
  });

  describe('Request Interceptor', () => {
    it('should have request interceptor configured', () => {
      expect(apiClient.interceptors.request).toBeDefined();
      // Verify interceptor handlers exist
      expect(apiClient.interceptors.request.handlers.length).toBeGreaterThan(0);
    });

    it('should add Authorization header when idToken exists', async () => {
      // Mock auth store with a token
      const mockIdToken = 'test-token-123';
      vi.mocked(useAuthStore).mockReturnValue({
        idToken: mockIdToken,
        refreshToken: vi.fn(),
        logout: vi.fn()
      } as any);

      // Create a mock config object
      const config: InternalAxiosRequestConfig = {
        headers: {} as any,
        url: '/test',
        method: 'get'
      } as InternalAxiosRequestConfig;

      // Get the request interceptor handler
      const requestInterceptor = apiClient.interceptors.request.handlers[0];

      // Call the fulfilled handler
      const result = await requestInterceptor.fulfilled(config);

      // Verify Authorization header was set
      expect(result.headers.Authorization).toBe(`Bearer ${mockIdToken}`);
    });

    it('should not add Authorization header when idToken is null', async () => {
      // Mock auth store without a token
      vi.mocked(useAuthStore).mockReturnValue({
        idToken: null,
        refreshToken: vi.fn(),
        logout: vi.fn()
      } as any);

      // Create a mock config object
      const config: InternalAxiosRequestConfig = {
        headers: {} as any,
        url: '/test',
        method: 'get'
      } as InternalAxiosRequestConfig;

      // Get the request interceptor handler
      const requestInterceptor = apiClient.interceptors.request.handlers[0];

      // Call the fulfilled handler
      const result = await requestInterceptor.fulfilled(config);

      // Verify Authorization header was not set
      expect(result.headers.Authorization).toBeUndefined();
    });
  });

  describe('Response Interceptor', () => {
    it('should have response interceptor configured', () => {
      expect(apiClient.interceptors.response).toBeDefined();
      // Verify interceptor handlers exist
      expect(apiClient.interceptors.response.handlers.length).toBeGreaterThan(0);
    });

    it('should pass through successful responses unchanged', async () => {
      // Create a mock successful response
      const mockResponse = {
        data: { id: 1, name: 'Test' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config: {} as any
      };

      // Get the response interceptor handler
      const responseInterceptor = apiClient.interceptors.response.handlers[0];

      // Call the fulfilled handler
      const result = await responseInterceptor.fulfilled(mockResponse);

      // Verify response is returned unchanged
      expect(result).toBe(mockResponse);
      expect(result.data).toEqual({ id: 1, name: 'Test' });
      expect(result.status).toBe(200);
    });
  });
});
