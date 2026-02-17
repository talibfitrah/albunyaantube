import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  searchYouTube,
  getChannelDetails,
  getChannelVideos,
  getChannelShorts,
  getChannelLiveStreams,
  getChannelPlaylists,
  getPlaylistDetails,
  getPlaylistVideos,
  toggleIncludeState
} from '@/services/youtubeService';
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
          type: 'channel',
          title: 'Test Channel',
          thumbnailUrl: 'https://example.com/thumb.jpg',
          subscriberCount: 1000
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
          type: 'playlist',
          title: 'Test Playlist',
          thumbnailUrl: 'https://example.com/thumb.jpg',
          itemCount: 10,
          channelId: 'channel-1',
          channelTitle: 'Owner Channel'
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
          id: 'video-1',
          type: 'video',
          title: 'Test Video',
          thumbnailUrl: 'https://example.com/thumb.jpg',
          publishedAt: '2024-01-01T00:00:00Z',
          channelId: 'channel-1',
          channelTitle: 'Owner Channel',
          duration: 'PT4M13S',
          viewCount: 5000
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
      const mockVideos = { items: [{ id: 'video-1' }], nextPageToken: null };
      const mockPlaylists = { items: [{ id: 'playlist-1' }], nextPageToken: null };

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

  describe('getChannelVideos', () => {
    it('should fetch paginated videos', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          items: [{ id: 'vid-1', name: 'Video 1', thumbnailUrl: '', uploadDate: '2024-01-01' }],
          nextPageToken: 'token2'
        }
      });

      const result = await getChannelVideos('UC123');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/channels/UC123/videos', { params: {} });
      expect(result.items).toHaveLength(1);
      expect(result.items[0].id).toBe('vid-1');
      expect(result.nextPageToken).toBe('token2');
    });

    it('should pass pageToken and search query', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { items: [], nextPageToken: null }
      });

      await getChannelVideos('UC123', 'page2', 'searchTerm');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/channels/UC123/videos', {
        params: { pageToken: 'page2', q: 'searchTerm' }
      });
    });
  });

  describe('getChannelShorts', () => {
    it('should fetch paginated shorts', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          items: [{ id: 'short-1', name: 'Short 1', thumbnailUrl: '', streamType: 'SHORT' }],
          nextPageToken: null
        }
      });

      const result = await getChannelShorts('UC123');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/channels/UC123/shorts', { params: {} });
      expect(result.items).toHaveLength(1);
      expect(result.items[0].streamType).toBe('SHORT');
    });

    it('should pass pageToken', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { items: [], nextPageToken: null }
      });

      await getChannelShorts('UC123', 'page2');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/channels/UC123/shorts', {
        params: { pageToken: 'page2' }
      });
    });
  });

  describe('getChannelLiveStreams', () => {
    it('should fetch paginated live streams', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          items: [{ id: 'live-1', name: 'Live 1', thumbnailUrl: '', streamType: 'LIVESTREAM' }],
          nextPageToken: 'liveToken2'
        }
      });

      const result = await getChannelLiveStreams('UC123');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/channels/UC123/livestreams', { params: {} });
      expect(result.items).toHaveLength(1);
      expect(result.items[0].streamType).toBe('LIVESTREAM');
      expect(result.nextPageToken).toBe('liveToken2');
    });
  });

  describe('getChannelPlaylists', () => {
    it('should fetch paginated playlists', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          items: [{ id: 'PL123', name: 'Playlist 1', thumbnailUrl: '', streamCount: 15 }],
          nextPageToken: null
        }
      });

      const result = await getChannelPlaylists('UC123');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/channels/UC123/playlists', { params: {} });
      expect(result.items).toHaveLength(1);
      expect(result.items[0].id).toBe('PL123');
      expect(result.items[0].itemCount).toBe(15);
    });
  });

  describe('getPlaylistDetails', () => {
    it('should fetch playlist details', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          name: 'My Playlist',
          thumbnailUrl: 'https://example.com/thumb.jpg',
          streamCount: 42
        }
      });

      const result = await getPlaylistDetails('PL123');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/playlists/PL123');
      expect(result.title).toBe('My Playlist');
      expect(result.itemCount).toBe(42);
    });
  });

  describe('getPlaylistVideos', () => {
    it('should fetch paginated playlist videos', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: {
          items: [{ id: 'vid-1', name: 'Video 1', thumbnailUrl: '' }],
          nextPageToken: 'plToken2'
        }
      });

      const result = await getPlaylistVideos('PL123');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/playlists/PL123/videos', { params: {} });
      expect(result.items).toHaveLength(1);
      expect(result.items[0].videoId).toBe('vid-1');
      expect(result.nextPageToken).toBe('plToken2');
    });

    it('should pass pageToken and search query', async () => {
      vi.mocked(apiClient.get).mockResolvedValueOnce({
        data: { items: [], nextPageToken: null }
      });

      await getPlaylistVideos('PL123', 'page2', 'query');

      expect(apiClient.get).toHaveBeenCalledWith('/api/admin/youtube/playlists/PL123/videos', {
        params: { pageToken: 'page2', q: 'query' }
      });
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
