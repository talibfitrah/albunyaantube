import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { apiClient } from '@/services/api/client';

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
  });

  describe('Response Interceptor', () => {
    it('should have response interceptor configured', () => {
      expect(apiClient.interceptors.response).toBeDefined();
      // Verify interceptor handlers exist
      expect(apiClient.interceptors.response.handlers.length).toBeGreaterThan(0);
    });
  });
});
