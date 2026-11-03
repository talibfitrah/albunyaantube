import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mockFetch = vi.fn();

describe('youtubeDataApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('fetch', mockFetch);
    vi.stubEnv('VITE_YOUTUBE_API_KEY', 'test-api-key');
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  describe('listChannelUploads - UC prefix guard', () => {
    it('should use uploads playlist (UU) for UC-prefixed channel IDs', async () => {
      // Mock playlistItems.list response
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({
          items: [{
            snippet: {
              title: 'Test Video',
              description: '',
              thumbnails: { high: { url: 'thumb.jpg' } },
              resourceId: { kind: 'youtube#video', videoId: 'vid123' },
              position: 0
            }
          }],
          nextPageToken: undefined
        })
      });

      // Mock videos.list enrichment
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({
          items: [{
            id: 'vid123',
            snippet: { title: 'Test Video', description: '', channelId: 'UC123', channelTitle: 'Channel', publishedAt: '2024-01-01', thumbnails: { high: { url: 'thumb.jpg' } } },
            contentDetails: { duration: 'PT5M30S' },
            statistics: { viewCount: '1000' }
          }]
        })
      });

      const { listChannelUploads } = await import('@/services/youtubeDataApi');
      const result = await listChannelUploads('UC123');

      // Should have called playlistItems with UU123 (not search)
      const firstCallUrl = mockFetch.mock.calls[0][0];
      expect(firstCallUrl).toContain('playlistItems');
      expect(firstCallUrl).toContain('UU123');
      expect(result.items).toHaveLength(1);
    });

    it('should fall back to searchChannelVideos for non-UC channel IDs', async () => {
      // Mock search.list response
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({
          items: [{
            id: { kind: 'youtube#video', videoId: 'vid456' },
            snippet: {
              title: 'Search Video',
              description: '',
              publishedAt: '2024-01-01',
              channelId: 'CustomChannel',
              channelTitle: 'Custom',
              thumbnails: { high: { url: 'thumb.jpg' } }
            }
          }],
          nextPageToken: undefined
        })
      });

      // Mock videos.list enrichment
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({
          items: [{
            id: 'vid456',
            snippet: { title: 'Search Video', description: '', channelId: 'CustomChannel', channelTitle: 'Custom', publishedAt: '2024-01-01', thumbnails: { high: { url: 'thumb.jpg' } } },
            contentDetails: { duration: 'PT10M' },
            statistics: { viewCount: '500' }
          }]
        })
      });

      const { listChannelUploads } = await import('@/services/youtubeDataApi');
      const result = await listChannelUploads('CustomChannel');

      // Should have called search (not playlistItems) since channelId doesn't start with UC
      const firstCallUrl = mockFetch.mock.calls[0][0];
      expect(firstCallUrl).toContain('search');
      expect(firstCallUrl).not.toContain('playlistItems');
      expect(result.items).toHaveLength(1);
    });
  });

  // A hung request must fail (into the caller's error state) instead of spinning forever.
  // Safari < 16 has no AbortSignal.timeout, and Safari can reject an aborted body read with a
  // plain AbortError, so both are simulated here.
  it('fails a request that does not answer within 30 seconds', async () => {
    vi.useFakeTimers();
    const timeout = AbortSignal.timeout;
    (AbortSignal as { timeout?: unknown }).timeout = undefined;
    try {
      mockFetch.mockImplementation((_url: string, init?: RequestInit) => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('The operation was aborted.', 'AbortError')));
      }));
      const { listPlaylistVideos } = await import('@/services/youtubeDataApi');
      const outcome = listPlaylistVideos('PL123').then(() => 'resolved', (e: Error) => e.message);

      await vi.advanceTimersByTimeAsync(29_000);
      expect(await Promise.race([outcome, 'pending'])).toBe('pending');
      await vi.advanceTimersByTimeAsync(1_000);
      expect(await Promise.race([outcome, 'pending'])).toBe('YouTube API request timed out');
    } finally {
      AbortSignal.timeout = timeout;
      vi.useRealTimers();
    }
  });

  describe('isYouTubeDataApiAvailable', () => {
    it('should return true when API key is configured', async () => {
      const { isYouTubeDataApiAvailable } = await import('@/services/youtubeDataApi');
      expect(isYouTubeDataApiAvailable()).toBe(true);
    });
  });
});
