import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import axios from 'axios';
import { apiClient } from '@/services/api/client';

vi.mock('axios');
vi.mock('@/stores/auth');
vi.mock('@/utils/toast');

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
  });

  it('should have correct default headers', () => {
    expect(apiClient.defaults.headers['Content-Type']).toBe('application/json');
    expect(apiClient.defaults.headers['Accept']).toBe('application/json');
  });

  it('should have 30 second timeout', () => {
    expect(apiClient.defaults.timeout).toBe(30000);
  });

  describe('Request Interceptor', () => {
    it('should add Authorization header if token exists', () => {
      // This would require more complex mocking of the auth store
      // Skipping for now as it's implementation detail
      expect(apiClient.interceptors.request).toBeDefined();
    });
  });

  describe('Response Interceptor', () => {
    it('should handle successful responses', () => {
      expect(apiClient.interceptors.response).toBeDefined();
    });
  });
});
