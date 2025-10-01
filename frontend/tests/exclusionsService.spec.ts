import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchExclusionsPage,
  createExclusion,
  updateExclusion,
  deleteExclusion
} from '@/services/exclusions';
import type { ExclusionPage } from '@/types/exclusions';

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    session: { accessToken: 'abc123' },
    refresh: vi.fn()
  })
}));

const originalFetch = global.fetch;

const samplePage: ExclusionPage = {
  data: [],
  pageInfo: {
    cursor: null,
    nextCursor: null,
    hasNext: false,
    limit: 20
  }
};

describe('exclusions service', () => {
  beforeEach(() => {
    global.fetch = vi.fn(async () =>
      new Response(JSON.stringify(samplePage), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  afterAll(() => {
    global.fetch = originalFetch;
  });

  it('fetches exclusions with query parameters and auth header', async () => {
    await fetchExclusionsPage({
      cursor: 'CURSOR_TOKEN',
      limit: 10,
      parentType: 'CHANNEL',
      excludeType: 'VIDEO',
      search: 'halaqa'
    });

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [url, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
    expect(url).toBe(
      'http://localhost:8080/api/v1/exclusions?cursor=CURSOR_TOKEN&limit=10&parentType=CHANNEL&excludeType=VIDEO&search=halaqa'
    );
    const headers = init.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer abc123');
    expect(headers.get('Accept')).toBe('application/json');
  });

  it('creates an exclusion with JSON body', async () => {
    await createExclusion({
      parentType: 'CHANNEL',
      parentId: 'channel:abc',
      excludeType: 'VIDEO',
      excludeId: 'video:def',
      reason: 'Manual QA'
    });

    const [, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('POST');
    expect(init.body).toBe(
      JSON.stringify({
        parentType: 'CHANNEL',
        parentId: 'channel:abc',
        excludeType: 'VIDEO',
        excludeId: 'video:def',
        reason: 'Manual QA'
      })
    );
  });

  it('updates exclusion reason via PATCH', async () => {
    await updateExclusion('exclusion-1', { reason: 'Updated note' });

    const [url, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:8080/api/v1/exclusions/exclusion-1');
    expect(init.method).toBe('PATCH');
    expect(init.body).toBe(JSON.stringify({ reason: 'Updated note' }));
  });

  it('deletes exclusion by id', async () => {
    await deleteExclusion('exclusion-9');

    const [url, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:8080/api/v1/exclusions/exclusion-9');
    expect(init.method).toBe('DELETE');
  });
});
