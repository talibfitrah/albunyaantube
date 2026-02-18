import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// We need to test the actual module logic, so we mock fetch globally
// and the env variable
const mockFetch = vi.fn();
global.fetch = mockFetch;

// Mock the env to provide API key
vi.stubEnv('VITE_YOUTUBE_API_KEY', 'test-api-key');

describe('youtubeDataApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
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

  describe('isYouTubeDataApiAvailable', () => {
    it('should return true when API key is configured', async () => {
      const { isYouTubeDataApiAvailable } = await import('@/services/youtubeDataApi');
      expect(isYouTubeDataApiAvailable()).toBe(true);
    });
  });
});
