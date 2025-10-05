import { vi } from 'vitest';
import type { AxiosInstance } from 'axios';

/**
 * Creates a mock Axios client for testing API calls.
 *
 * Usage:
 * ```ts
 * import { createMockApiClient } from '../utils/mockApiClient';
 *
 * const mockClient = createMockApiClient();
 * mockClient.get.mockResolvedValue({ data: { id: '1', name: 'Test' } });
 * ```
 */
export function createMockApiClient(): jest.Mocked<AxiosInstance> {
  return {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
    request: vi.fn(),
    head: vi.fn(),
    options: vi.fn(),
    defaults: {} as any,
    interceptors: {
      request: { use: vi.fn(), eject: vi.fn(), clear: vi.fn() },
      response: { use: vi.fn(), eject: vi.fn(), clear: vi.fn() }
    } as any,
    getUri: vi.fn(),
    postForm: vi.fn(),
    putForm: vi.fn(),
    patchForm: vi.fn()
  } as any;
}

/**
 * Mock successful API response
 */
export function mockSuccessResponse<T>(data: T) {
  return Promise.resolve({ data, status: 200, statusText: 'OK', headers: {}, config: {} as any });
}

/**
 * Mock error API response
 */
export function mockErrorResponse(status: number, message: string) {
  const error: any = new Error(message);
  error.response = {
    status,
    statusText: message,
    data: { message },
    headers: {},
    config: {} as any
  };
  return Promise.reject(error);
}

/**
 * Mock paginated API response
 */
export function mockPaginatedResponse<T>(items: T[], total: number, page: number = 1, pageSize: number = 20) {
  return mockSuccessResponse({
    items,
    total,
    page,
    pageSize,
    totalPages: Math.ceil(total / pageSize)
  });
}
