import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchExclusionsPage,
  createExclusion,
  fetchChannelExclusions,
  addChannelExclusion,
  removeChannelExclusion,
  fetchPlaylistExclusions,
  addPlaylistExclusion,
  removePlaylistExclusion
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

  // Legacy tests - these functions are deprecated and replaced with channel/playlist specific functions
  it.skip('fetches exclusions with query parameters and auth header', async () => {
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

  it.skip('creates an exclusion with JSON body', async () => {
    // createExclusion function no longer exists - use addChannelExclusion or addPlaylistExclusion
  });

  it.skip('updates exclusion reason via PATCH', async () => {
    // updateExclusion function no longer exists
  });

  it.skip('deletes exclusion by id', async () => {
    // deleteExclusion function no longer exists - use removeChannelExclusion or removePlaylistExclusion
  });

  describe('Channel Exclusions', () => {
    it('fetches channel exclusions', async () => {
      const mockExclusions = {
        videos: ['video1', 'video2'],
        playlists: ['playlist1'],
        liveStreams: [],
        shorts: [],
        posts: []
      };

      global.fetch = vi.fn(async () =>
        new Response(JSON.stringify(mockExclusions), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      );

      const result = await fetchChannelExclusions('channel-123');

      expect(global.fetch).toHaveBeenCalledTimes(1);
      const [url] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
      // In test mode (DEV), uses relative URL for Vite proxy
      expect(url).toBe('/api/admin/channels/channel-123/exclusions');
      expect(result).toEqual(mockExclusions);
    });

    it('adds channel video exclusion', async () => {
      const mockUpdatedExclusions = {
        videos: ['video1', 'video2', 'video3'],
        playlists: [],
        liveStreams: [],
        shorts: [],
        posts: []
      };

      global.fetch = vi.fn(async () =>
        new Response(JSON.stringify(mockUpdatedExclusions), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      );

      const result = await addChannelExclusion('channel-123', 'video', 'video3');

      const [url, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
      // In test mode (DEV), uses relative URL for Vite proxy
      expect(url).toBe('/api/admin/channels/channel-123/exclusions/video/video3');
      expect(init.method).toBe('POST');
      expect(result).toEqual(mockUpdatedExclusions);
    });

    it('removes channel playlist exclusion', async () => {
      const mockUpdatedExclusions = {
        videos: [],
        playlists: [],
        liveStreams: [],
        shorts: [],
        posts: []
      };

      global.fetch = vi.fn(async () =>
        new Response(JSON.stringify(mockUpdatedExclusions), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      );

      const result = await removeChannelExclusion('channel-123', 'playlist', 'playlist1');

      const [url, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
      // In test mode (DEV), uses relative URL for Vite proxy
      expect(url).toBe('/api/admin/channels/channel-123/exclusions/playlist/playlist1');
      expect(init.method).toBe('DELETE');
      expect(result).toEqual(mockUpdatedExclusions);
    });
  });

  describe('Playlist Exclusions', () => {
    it('fetches playlist exclusions', async () => {
      const mockExclusions = ['video1', 'video2'];

      global.fetch = vi.fn(async () =>
        new Response(JSON.stringify(mockExclusions), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      );

      const result = await fetchPlaylistExclusions('playlist-123');

      expect(global.fetch).toHaveBeenCalledTimes(1);
      const [url] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
      // In test mode (DEV), uses relative URL for Vite proxy; playlists go through registry endpoint
      expect(url).toBe('/api/admin/registry/playlists/playlist-123/exclusions');
      expect(result).toEqual(mockExclusions);
    });

    it('adds playlist video exclusion', async () => {
      const mockUpdatedExclusions = ['video1', 'video2', 'video3'];

      global.fetch = vi.fn(async () =>
        new Response(JSON.stringify(mockUpdatedExclusions), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      );

      const result = await addPlaylistExclusion('playlist-123', 'video3');

      const [url, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
      // In test mode (DEV), uses relative URL for Vite proxy; playlists go through registry endpoint
      expect(url).toBe('/api/admin/registry/playlists/playlist-123/exclusions/video3');
      expect(init.method).toBe('POST');
      expect(result).toEqual(mockUpdatedExclusions);
    });

    it('removes playlist video exclusion', async () => {
      const mockUpdatedExclusions = ['video2'];

      global.fetch = vi.fn(async () =>
        new Response(JSON.stringify(mockUpdatedExclusions), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      );

      const result = await removePlaylistExclusion('playlist-123', 'video1');

      const [url, init] = (global.fetch as vi.Mock).mock.calls[0] as [string, RequestInit];
      // In test mode (DEV), uses relative URL for Vite proxy; playlists go through registry endpoint
      expect(url).toBe('/api/admin/registry/playlists/playlist-123/exclusions/video1');
      expect(init.method).toBe('DELETE');
      expect(result).toEqual(mockUpdatedExclusions);
    });
  });
});
