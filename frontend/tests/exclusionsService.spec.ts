import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchExclusionsPage,
  fetchChannelExclusions,
  addChannelExclusion,
  removeChannelExclusion,
  fetchPlaylistExclusions,
  addPlaylistExclusion,
  removePlaylistExclusion
} from '@/services/exclusions';

// Mock the apiClient module
vi.mock('@/services/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn()
  }
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    session: { accessToken: 'abc123' },
    idToken: 'test-token',
    refresh: vi.fn()
  })
}));

import apiClient from '@/services/api/client';

describe('exclusions service', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // Legacy tests - these functions are deprecated and replaced with channel/playlist specific functions
  it.skip('fetches exclusions with query parameters and auth header', async () => {
    // fetchExclusionsPage returns mock data for now
  });

  describe('Channel Exclusions', () => {
    it('fetches channel exclusions', async () => {
      const mockExclusions = {
        videos: ['video1', 'video2'],
        playlists: ['playlist1']
      };

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockExclusions });

      const result = await fetchChannelExclusions('channel-123');

      expect(apiClient.get).toHaveBeenCalledWith('/admin/channels/channel-123/exclusions');
      expect(result).toEqual(mockExclusions);
    });

    it('adds channel video exclusion', async () => {
      const mockUpdatedExclusions = {
        videos: ['video1', 'video2', 'video3'],
        playlists: []
      };

      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: mockUpdatedExclusions });

      const result = await addChannelExclusion('channel-123', 'video', 'video3');

      expect(apiClient.post).toHaveBeenCalledWith('/admin/channels/channel-123/exclusions/video/video3');
      expect(result).toEqual(mockUpdatedExclusions);
    });

    it('removes channel playlist exclusion', async () => {
      const mockUpdatedExclusions = {
        videos: [],
        playlists: []
      };

      vi.mocked(apiClient.delete).mockResolvedValueOnce({ data: mockUpdatedExclusions });

      const result = await removeChannelExclusion('channel-123', 'playlist', 'playlist1');

      expect(apiClient.delete).toHaveBeenCalledWith('/admin/channels/channel-123/exclusions/playlist/playlist1');
      expect(result).toEqual(mockUpdatedExclusions);
    });
  });

  describe('Playlist Exclusions', () => {
    it('fetches playlist exclusions', async () => {
      const mockExclusions = ['video1', 'video2'];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockExclusions });

      const result = await fetchPlaylistExclusions('playlist-123');

      expect(apiClient.get).toHaveBeenCalledWith('/admin/playlists/playlist-123/exclusions');
      expect(result).toEqual(mockExclusions);
    });

    it('adds playlist video exclusion', async () => {
      const mockUpdatedExclusions = ['video1', 'video2', 'video3'];

      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: mockUpdatedExclusions });

      const result = await addPlaylistExclusion('playlist-123', 'video3');

      expect(apiClient.post).toHaveBeenCalledWith('/admin/playlists/playlist-123/exclusions/video3');
      expect(result).toEqual(mockUpdatedExclusions);
    });

    it('removes playlist video exclusion', async () => {
      const mockUpdatedExclusions = ['video2'];

      vi.mocked(apiClient.delete).mockResolvedValueOnce({ data: mockUpdatedExclusions });

      const result = await removePlaylistExclusion('playlist-123', 'video1');

      expect(apiClient.delete).toHaveBeenCalledWith('/admin/playlists/playlist-123/exclusions/video1');
      expect(result).toEqual(mockUpdatedExclusions);
    });
  });

  describe('Workspace Exclusions', () => {
    it('returns empty page for fetchExclusionsPage', async () => {
      const result = await fetchExclusionsPage();

      expect(result).toEqual({
        data: [],
        pageInfo: {
          cursor: null,
          nextCursor: null,
          hasNext: false
        }
      });
    });
  });
});
