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
import * as youtubeDataApi from '@/services/youtubeDataApi';

vi.mock('@/services/api/client');
vi.mock('@/services/youtubeDataApi');

describe('YouTubeService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('searchYouTube', () => {
    it('should throw when YouTube Data API key is not configured', async () => {
      vi.mocked(youtubeDataApi.isYouTubeDataApiAvailable).mockReturnValue(false);

      await expect(searchYouTube('test', 'channels')).rejects.toThrow(
        'YouTube Data API key not configured'
      );
    });
  });

  describe('getChannelDetails', () => {
    it('should fetch channel details with videos and playlists', async () => {
      vi.mocked(youtubeDataApi.getChannel).mockResolvedValue({
        id: 'UC123',
        name: 'Test Channel',
        description: 'A test channel',
        thumbnailUrl: 'https://example.com/thumb.jpg',
        subscriberCount: 1000,
        videoCount: 50,
        viewCount: 100000
      });

      vi.mocked(youtubeDataApi.listChannelUploads).mockResolvedValue({
        items: [{
          id: 'vid-1',
          title: 'Video 1',
          thumbnailUrl: 'https://example.com/vid.jpg',
          publishedAt: '2024-01-01',
          durationSeconds: 120,
          viewCount: 500,
          channelId: 'UC123',
          channelTitle: 'Test Channel'
        }]
      });

      vi.mocked(youtubeDataApi.listChannelPlaylists).mockResolvedValue({
        items: [{
          id: 'PL123',
          title: 'Playlist 1',
          thumbnailUrl: 'https://example.com/pl.jpg',
          itemCount: 10,
          channelId: 'UC123',
          channelTitle: 'Test Channel'
        }]
      });

      const result = await getChannelDetails('UC123');

      expect(youtubeDataApi.getChannel).toHaveBeenCalledWith('UC123');
      expect(youtubeDataApi.listChannelUploads).toHaveBeenCalledWith('UC123');
      expect(youtubeDataApi.listChannelPlaylists).toHaveBeenCalledWith('UC123');
      expect(result.channel).toBeDefined();
      expect(result.channel.name).toBe('Test Channel');
      expect(result.videos).toHaveLength(1);
      expect(result.playlists).toHaveLength(1);
    });
  });

  describe('getChannelVideos', () => {
    it('should use uploads playlist when no search query (2 units)', async () => {
      vi.mocked(youtubeDataApi.listChannelUploads).mockResolvedValue({
        items: [{
          id: 'vid-1',
          title: 'Video 1',
          thumbnailUrl: '',
          publishedAt: '2024-01-01',
          durationSeconds: 120,
          viewCount: 500,
          channelId: 'UC123',
          channelTitle: 'Test Channel'
        }],
        nextPageToken: 'token2'
      });

      const result = await getChannelVideos('UC123');

      expect(youtubeDataApi.listChannelUploads).toHaveBeenCalledWith('UC123', undefined);
      expect(youtubeDataApi.searchChannelVideos).not.toHaveBeenCalled();
      expect(result.items).toHaveLength(1);
      expect(result.items[0].id).toBe('vid-1');
      expect(result.nextPageToken).toBe('token2');
    });

    it('should use search when query is provided (101 units)', async () => {
      vi.mocked(youtubeDataApi.searchChannelVideos).mockResolvedValue({
        items: [],
        nextPageToken: undefined
      });

      await getChannelVideos('UC123', 'page2', 'searchTerm');

      expect(youtubeDataApi.searchChannelVideos).toHaveBeenCalledWith('UC123', 'page2', 'searchTerm');
      expect(youtubeDataApi.listChannelUploads).not.toHaveBeenCalled();
    });

    it('should pass pageToken for uploads', async () => {
      vi.mocked(youtubeDataApi.listChannelUploads).mockResolvedValue({
        items: [],
        nextPageToken: undefined
      });

      await getChannelVideos('UC123', 'page2');

      expect(youtubeDataApi.listChannelUploads).toHaveBeenCalledWith('UC123', 'page2');
    });
  });

  describe('getChannelShorts', () => {
    it('should fetch paginated shorts', async () => {
      vi.mocked(youtubeDataApi.searchChannelShorts).mockResolvedValue({
        items: [{
          id: 'short-1',
          title: 'Short 1',
          thumbnailUrl: '',
          publishedAt: '2024-01-01',
          durationSeconds: 30,
          viewCount: 100,
          channelId: 'UC123',
          channelTitle: 'Test Channel'
        }]
      });

      const result = await getChannelShorts('UC123');

      expect(youtubeDataApi.searchChannelShorts).toHaveBeenCalledWith('UC123', undefined);
      expect(result.items).toHaveLength(1);
      expect(result.items[0].streamType).toBe('SHORT');
    });

    it('should pass pageToken', async () => {
      vi.mocked(youtubeDataApi.searchChannelShorts).mockResolvedValue({
        items: []
      });

      await getChannelShorts('UC123', 'page2');

      expect(youtubeDataApi.searchChannelShorts).toHaveBeenCalledWith('UC123', 'page2');
    });
  });

  describe('getChannelLiveStreams', () => {
    it('should fetch paginated live streams', async () => {
      vi.mocked(youtubeDataApi.searchChannelLiveStreams).mockResolvedValue({
        items: [{
          id: 'live-1',
          title: 'Live 1',
          thumbnailUrl: '',
          publishedAt: '2024-01-01',
          durationSeconds: 3600,
          viewCount: 1000,
          channelId: 'UC123',
          channelTitle: 'Test Channel'
        }],
        nextPageToken: 'liveToken2'
      });

      const result = await getChannelLiveStreams('UC123');

      expect(youtubeDataApi.searchChannelLiveStreams).toHaveBeenCalledWith('UC123', undefined);
      expect(result.items).toHaveLength(1);
      expect(result.items[0].streamType).toBe('LIVESTREAM');
      expect(result.nextPageToken).toBe('liveToken2');
    });
  });

  describe('getChannelPlaylists', () => {
    it('should fetch paginated playlists', async () => {
      vi.mocked(youtubeDataApi.listChannelPlaylists).mockResolvedValue({
        items: [{
          id: 'PL123',
          title: 'Playlist 1',
          thumbnailUrl: '',
          itemCount: 15,
          channelId: 'UC123',
          channelTitle: 'Test Channel'
        }]
      });

      const result = await getChannelPlaylists('UC123');

      expect(youtubeDataApi.listChannelPlaylists).toHaveBeenCalledWith('UC123', undefined);
      expect(result.items).toHaveLength(1);
      expect(result.items[0].id).toBe('PL123');
      expect(result.items[0].itemCount).toBe(15);
    });
  });

  describe('getPlaylistDetails', () => {
    it('should fetch playlist details', async () => {
      vi.mocked(youtubeDataApi.getPlaylist).mockResolvedValue({
        id: 'PL123',
        title: 'My Playlist',
        thumbnailUrl: 'https://example.com/thumb.jpg',
        itemCount: 42,
        channelId: 'UC123',
        channelTitle: 'Test Channel'
      });

      const result = await getPlaylistDetails('PL123');

      expect(youtubeDataApi.getPlaylist).toHaveBeenCalledWith('PL123');
      expect(result.title).toBe('My Playlist');
      expect(result.itemCount).toBe(42);
    });
  });

  describe('getPlaylistVideos', () => {
    it('should fetch paginated playlist videos', async () => {
      vi.mocked(youtubeDataApi.listPlaylistVideos).mockResolvedValue({
        items: [{
          id: 'vid-1',
          title: 'Video 1',
          thumbnailUrl: '',
          publishedAt: '2024-01-01',
          durationSeconds: 120,
          viewCount: 500,
          channelId: 'UC123',
          channelTitle: 'Test Channel'
        }],
        nextPageToken: 'plToken2'
      });

      const result = await getPlaylistVideos('PL123');

      expect(youtubeDataApi.listPlaylistVideos).toHaveBeenCalledWith('PL123', undefined);
      expect(result.items).toHaveLength(1);
      expect(result.items[0].videoId).toBe('vid-1');
      expect(result.nextPageToken).toBe('plToken2');
    });

    it('should pass pageToken', async () => {
      vi.mocked(youtubeDataApi.listPlaylistVideos).mockResolvedValue({
        items: [],
        nextPageToken: undefined
      });

      await getPlaylistVideos('PL123', 'page2');

      expect(youtubeDataApi.listPlaylistVideos).toHaveBeenCalledWith('PL123', 'page2');
    });

    it('should filter items client-side when searchQuery is provided', async () => {
      vi.mocked(youtubeDataApi.listPlaylistVideos).mockResolvedValue({
        items: [
          { id: 'vid-1', title: 'React Tutorial', thumbnailUrl: '', publishedAt: '', durationSeconds: 0, viewCount: 0, channelId: '', channelTitle: '' },
          { id: 'vid-2', title: 'Vue Guide', thumbnailUrl: '', publishedAt: '', durationSeconds: 0, viewCount: 0, channelId: '', channelTitle: '' },
          { id: 'vid-3', title: 'React Hooks', thumbnailUrl: '', publishedAt: '', durationSeconds: 0, viewCount: 0, channelId: '', channelTitle: '' }
        ]
      });

      const result = await getPlaylistVideos('PL123', undefined, 'react');

      expect(result.items).toHaveLength(2);
      expect(result.items[0].title).toBe('React Tutorial');
      expect(result.items[1].title).toBe('React Hooks');
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

    it('should toggle video include state', async () => {
      vi.mocked(apiClient.patch).mockResolvedValueOnce({ data: {} });

      await toggleIncludeState('video-1', 'video', 'INCLUDED');

      expect(apiClient.patch).toHaveBeenCalledWith('/api/admin/registry/videos/video-1/toggle');
    });
  });
});
