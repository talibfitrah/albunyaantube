import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { searchYouTube, getChannelDetails, toggleIncludeState } from '@/services/youtubeService';
import apiClient from '@/services/api/client';

vi.mock('@/services/api/client');

describe('YouTubeService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('searchYouTube', () => {
    it('should search for channels and transform results', async () => {
      const mockChannels = [
        {
          id: 'channel-1',
          snippet: {
            title: 'Test Channel',
            thumbnails: { default: { url: 'https://example.com/thumb.jpg' } }
          },
          statistics: { subscriberCount: '1000' }
        }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockChannels });

      const result = await searchYouTube('test', 'channels');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/search/channels', {
        params: { query: 'test' }
      });
      expect(result.channels).toHaveLength(1);
      expect(result.channels[0]).toMatchObject({
        id: 'channel-1',
        name: 'Test Channel',
        subscriberCount: 1000
      });
    });

    it('should search for playlists', async () => {
      const mockPlaylists = [
        {
          id: 'playlist-1',
          snippet: {
            title: 'Test Playlist',
            thumbnails: { default: { url: 'https://example.com/thumb.jpg' } },
            channelId: 'channel-1'
          },
          contentDetails: { itemCount: '10' }
        }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockPlaylists });

      const result = await searchYouTube('test', 'playlists');

      expect(result.playlists).toHaveLength(1);
      expect(result.playlists[0].title).toBe('Test Playlist');
      expect(result.playlists[0].itemCount).toBe(10);
    });

    it('should search for videos', async () => {
      const mockVideos = [
        {
          id: { videoId: 'video-1' },
          snippet: {
            title: 'Test Video',
            thumbnails: { default: { url: 'https://example.com/thumb.jpg' } },
            publishedAt: '2024-01-01T00:00:00Z',
            channelId: 'channel-1'
          }
        }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockVideos });

      const result = await searchYouTube('test', 'videos');

      expect(result.videos).toHaveLength(1);
      expect(result.videos[0].title).toBe('Test Video');
    });
  });

  describe('getChannelDetails', () => {
    it('should fetch channel details with videos and playlists', async () => {
      const mockChannel = {
        id: 'channel-1',
        snippet: { title: 'Test Channel' },
        statistics: { subscriberCount: '1000' }
      };
      const mockVideos = [{ id: 'video-1' }];
      const mockPlaylists = [{ id: 'playlist-1' }];

      vi.mocked(apiClient.get)
        .mockResolvedValueOnce({ data: mockChannel })
        .mockResolvedValueOnce({ data: mockVideos })
        .mockResolvedValueOnce({ data: mockPlaylists });

      const result = await getChannelDetails('channel-1');

      expect(apiClient.get).toHaveBeenCalledTimes(3);
      expect(result.channel).toBeDefined();
      expect(result.videos).toHaveLength(1);
      expect(result.playlists).toHaveLength(1);
    });
  });

  describe('toggleIncludeState', () => {
    it('should toggle channel include state', async () => {
      vi.mocked(apiClient.patch).mockResolvedValueOnce({ data: {} });

      await toggleIncludeState('channel-1', 'channel', 'INCLUDED');

      expect(apiClient.patch).toHaveBeenCalledWith('/api/admin/registry/channels/channel-1/toggle');
    });

    it('should toggle playlist include state', async () => {
      vi.mocked(apiClient.patch).mockResolvedValueOnce({ data: {} });

      await toggleIncludeState('playlist-1', 'playlist', 'INCLUDED');

      expect(apiClient.patch).toHaveBeenCalledWith('/api/admin/registry/playlists/playlist-1/toggle');
    });
  });
});
