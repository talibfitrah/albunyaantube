/**
 * YouTube Service
 *
 * All YouTube data fetching uses YouTube Data API v3 directly (requires VITE_YOUTUBE_API_KEY).
 * Registry operations (add to pending, check existing) always go through backend.
 */

import apiClient from './api/client';
import {
  isYouTubeDataApiAvailable,
  searchAll as ytSearchAll,
  searchByType as ytSearchByType,
  getChannel,
  listChannelUploads,
  searchChannelVideos as ytSearchChannelVideos,
  searchChannelShorts,
  searchChannelLiveStreams,
  listChannelPlaylists as ytListChannelPlaylists,
  getPlaylist,
  listPlaylistVideos as ytListPlaylistVideos
} from './youtubeDataApi';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';
import type {
  EnrichedSearchResult,
  Channel,
  Playlist,
  Video
} from '@/types/api';

interface YouTubeSearchResponse {
  channels: AdminSearchChannelResult[];
  playlists: AdminSearchPlaylistResult[];
  videos: AdminSearchVideoResult[];
  nextPageToken?: string;
  totalResults?: number;
}

/**
 * Search YouTube for channels, playlists, or videos with pagination support.
 *
 * Requires VITE_YOUTUBE_API_KEY to be configured.
 * Calls YouTube Data API v3 directly from the browser (no backend proxy).
 */
export async function searchYouTube(
  query: string,
  type: 'all' | 'channels' | 'playlists' | 'videos' = 'all',
  pageToken?: string
): Promise<YouTubeSearchResponse> {
  if (!isYouTubeDataApiAvailable()) {
    throw new Error('YouTube Data API key not configured. Set VITE_YOUTUBE_API_KEY in your environment.');
  }

  return searchYouTubeDirect(query, type, pageToken);
}

/**
 * Search using YouTube Data API v3 directly from the browser.
 */
async function searchYouTubeDirect(
  query: string,
  type: 'all' | 'channels' | 'playlists' | 'videos',
  pageToken?: string
): Promise<YouTubeSearchResponse> {
  if (type === 'all') {
    const response = await ytSearchAll(query, pageToken);
    const channels: EnrichedSearchResult[] = [];
    const playlists: EnrichedSearchResult[] = [];
    const videos: EnrichedSearchResult[] = [];

    response.items.forEach(item => {
      if (item.type === 'channel') channels.push(item);
      else if (item.type === 'playlist') playlists.push(item);
      else if (item.type === 'video') videos.push(item);
    });

    return {
      channels: transformChannelResults(channels),
      playlists: transformPlaylistResults(playlists),
      videos: transformVideoResults(videos),
      nextPageToken: response.nextPageToken || undefined,
      totalResults: response.totalResults
    };
  }

  const typeMap: Record<string, 'channel' | 'playlist' | 'video'> = {
    channels: 'channel',
    playlists: 'playlist',
    videos: 'video'
  };
  const results = await ytSearchByType(query, typeMap[type]);
  return {
    channels: type === 'channels' ? transformChannelResults(results) : [],
    playlists: type === 'playlists' ? transformPlaylistResults(results) : [],
    videos: type === 'videos' ? transformVideoResults(results) : []
  };
}

/**
 * Get channel details with videos and playlists.
 * Uses getChannel (1 unit) + listChannelUploads (2 units) + listChannelPlaylists (1 unit) = 4 units.
 */
export async function getChannelDetails(channelId: string) {
  const [channel, videosResult, playlistsResult] = await Promise.all([
    getChannel(channelId),
    listChannelUploads(channelId),
    ytListChannelPlaylists(channelId)
  ]);

  const channelAsSearchResult: EnrichedSearchResult = {
    id: channel.id,
    title: channel.name,
    thumbnailUrl: channel.thumbnailUrl,
    description: channel.description,
    subscriberCount: channel.subscriberCount,
    videoCount: channel.videoCount,
    type: 'channel'
  };

  const videosAsSearchResults: EnrichedSearchResult[] = videosResult.items.map(v => ({
    id: v.id,
    title: v.title,
    thumbnailUrl: v.thumbnailUrl,
    description: '',
    channelId: v.channelId || channelId,
    channelTitle: v.channelTitle || channel.name,
    viewCount: v.viewCount,
    duration: v.durationSeconds != null ? `PT${v.durationSeconds}S` : undefined,
    publishedAt: v.publishedAt,
    type: 'video'
  }));

  const playlistsAsSearchResults: EnrichedSearchResult[] = playlistsResult.items.map(p => ({
    id: p.id,
    title: p.title,
    thumbnailUrl: p.thumbnailUrl,
    description: '',
    channelId: p.channelId || channelId,
    channelTitle: p.channelTitle || channel.name,
    itemCount: p.itemCount,
    type: 'playlist'
  }));

  return {
    channel: transformChannelResult(channelAsSearchResult),
    videos: transformVideoResults(videosAsSearchResults),
    playlists: transformPlaylistResults(playlistsAsSearchResults)
  };
}

/**
 * Add a channel, playlist, or video to pending approval queue
 */
export async function addToPendingApprovals(
  item: AdminSearchChannelResult | AdminSearchPlaylistResult | AdminSearchVideoResult,
  itemType: 'channel' | 'playlist' | 'video',
  categoryIds: string[] = []
): Promise<void> {
  if (itemType === 'channel') {
    const channel = item as AdminSearchChannelResult;
    // Note: displayOrder is intentionally omitted - null until explicitly set via reorder
    const payload: Omit<Channel, 'id' | 'displayOrder'> = {
      youtubeId: channel.ytId || channel.id,
      name: channel.name || '',
      description: null, // AdminSearchChannelResult doesn't include description
      thumbnailUrl: channel.avatarUrl || null,
      subscribers: channel.subscriberCount || null,
      videoCount: null,
      categoryIds,
      status: 'PENDING'
    };
    await apiClient.post<Channel>('/api/admin/registry/channels', payload);
  } else if (itemType === 'playlist') {
    const playlist = item as AdminSearchPlaylistResult;
    // Note: displayOrder is intentionally omitted - null until explicitly set via reorder
    const payload: Omit<Playlist, 'id' | 'displayOrder'> = {
      youtubeId: playlist.ytId || playlist.id,
      title: playlist.title || '',
      description: null, // AdminSearchPlaylistResult doesn't include description
      thumbnailUrl: playlist.thumbnailUrl || null,
      itemCount: playlist.itemCount || null,
      categoryIds,
      status: 'PENDING',
      channelId: null
    };
    await apiClient.post<Playlist>('/api/admin/registry/playlists', payload);
  } else if (itemType === 'video') {
    const video = item as AdminSearchVideoResult;
    // Note: displayOrder is intentionally omitted - null until explicitly set via reorder
    const payload: Omit<Video, 'id' | 'displayOrder'> = {
      youtubeId: video.ytId || video.id,
      title: video.title || '',
      description: null, // AdminSearchVideoResult doesn't include description
      thumbnailUrl: video.thumbnailUrl || null,
      durationSeconds: video.durationSeconds || null,
      viewCount: video.viewCount || null,
      categoryIds,
      status: 'PENDING',
      channelId: null
      // Note: Video schema has no playlistId field
    };
    await apiClient.post<Video>('/api/admin/registry/videos', payload);
  }
}

/**
 * @deprecated Use addToPendingApprovals instead
 * Toggle include/exclude state for a channel or playlist
 */
export async function toggleIncludeState(
  itemId: string,
  itemType: 'channel' | 'playlist' | 'video',
  newState: 'INCLUDED' | 'NOT_INCLUDED'
): Promise<void> {
  let endpoint: string;
  if (itemType === 'channel') {
    endpoint = `/api/admin/registry/channels/${itemId}/toggle`;
  } else if (itemType === 'video') {
    endpoint = `/api/admin/registry/videos/${itemId}/toggle`;
  } else {
    endpoint = `/api/admin/registry/playlists/${itemId}/toggle`;
  }

  await apiClient.patch(endpoint);
}

// Transform EnrichedSearchResult to AdminSearchChannelResult
function transformChannelResult(channel: EnrichedSearchResult): AdminSearchChannelResult {
  return {
    id: channel.id || '',
    ytId: channel.id || '',
    name: channel.title || '',
    avatarUrl: channel.thumbnailUrl || '',
    subscriberCount: channel.subscriberCount || 0,
    publishedAt: channel.publishedAt,
    categories: [],
    includeState: 'NOT_INCLUDED',
    excludedItemCounts: { videos: 0, playlists: 0 },
    excludedPlaylistIds: [],
    excludedVideoIds: [],
    bulkEligible: true
  };
}

function transformChannelResults(channels: EnrichedSearchResult[]): AdminSearchChannelResult[] {
  return channels.map(transformChannelResult);
}

// Transform EnrichedSearchResult to AdminSearchPlaylistResult
function transformPlaylistResults(playlists: EnrichedSearchResult[]): AdminSearchPlaylistResult[] {
  return playlists.map(playlist => {
    return {
      id: playlist.id || '',
      ytId: playlist.id || '',
      title: playlist.title || '',
      thumbnailUrl: playlist.thumbnailUrl || '',
      videoThumbnails: playlist.videoThumbnails || [],
      itemCount: playlist.itemCount || 0,
      publishedAt: playlist.publishedAt,
      owner: {
        id: playlist.channelId || '',
        ytId: playlist.channelId || '',
        name: playlist.channelTitle || '',
        avatarUrl: '',
        subscriberCount: 0,
        categories: []
      },
      categories: [],
      downloadable: true,
      includeState: 'NOT_INCLUDED',
      parentChannelId: playlist.channelId || '',
      excludedVideoCount: 0,
      excludedVideoIds: [],
      bulkEligible: true
    };
  });
}

// Transform EnrichedSearchResult to AdminSearchVideoResult
function transformVideoResults(videos: EnrichedSearchResult[]): AdminSearchVideoResult[] {
  return videos.map(video => {
    return {
      id: video.id || '',
      ytId: video.id || '',
      title: video.title || '',
      thumbnailUrl: video.thumbnailUrl || '',
      durationSeconds: video.duration ? parseDuration(video.duration) : 0,
      publishedAt: video.publishedAt || '',
      viewCount: video.viewCount || 0,
      channel: {
        id: video.channelId || '',
        ytId: video.channelId || '',
        name: video.channelTitle || '',
        avatarUrl: '',
        subscriberCount: 0,
        categories: []
      },
      categories: [],
      bookmarked: null,
      downloaded: null,
      includeState: 'NOT_INCLUDED',
      parentChannelId: video.channelId || '',
      parentPlaylistIds: []
    };
  });
}

// Parse ISO 8601 duration to seconds (e.g., "PT1H2M30S" -> 3750)
function parseDuration(duration: string): number {
  if (!duration) return 0;

  const match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
  if (!match) return 0;

  const hours = parseInt(match[1] || '0');
  const minutes = parseInt(match[2] || '0');
  const seconds = parseInt(match[3] || '0');

  return hours * 3600 + minutes * 60 + seconds;
}

// Channel/Playlist browsing - Direct YouTube Data API v3

/**
 * Get videos from a YouTube channel with pagination.
 * Without query: uses uploads playlist (2 units). With query: uses search (101 units).
 */
export async function getChannelVideos(
  channelId: string,
  pageToken?: string,
  searchQuery?: string
) {
  // Use cheap uploads playlist when no search query, expensive search only when needed
  const result = searchQuery
    ? await ytSearchChannelVideos(channelId, pageToken, searchQuery)
    : await listChannelUploads(channelId, pageToken);

  return {
    items: result.items.map(v => ({
      id: v.id,
      title: v.title,
      thumbnailUrl: v.thumbnailUrl,
      publishedAt: v.publishedAt,
      streamType: 'VIDEO' as const
    })),
    nextPageToken: result.nextPageToken
  };
}

/**
 * Get shorts from a YouTube channel with pagination.
 * Uses search.list with videoDuration=short (101 units) - no cheaper alternative for shorts filtering.
 */
export async function getChannelShorts(
  channelId: string,
  pageToken?: string
) {
  const result = await searchChannelShorts(channelId, pageToken);

  return {
    items: result.items.map(v => ({
      id: v.id,
      title: v.title,
      thumbnailUrl: v.thumbnailUrl,
      publishedAt: v.publishedAt,
      streamType: 'SHORT' as const
    })),
    nextPageToken: result.nextPageToken
  };
}

/**
 * Get live streams from a YouTube channel with pagination.
 * Uses search.list with eventType=completed (101 units) - no cheaper alternative for live filter.
 */
export async function getChannelLiveStreams(
  channelId: string,
  pageToken?: string
) {
  const result = await searchChannelLiveStreams(channelId, pageToken);

  return {
    items: result.items.map(v => ({
      id: v.id,
      title: v.title,
      thumbnailUrl: v.thumbnailUrl,
      publishedAt: v.publishedAt,
      streamType: 'LIVESTREAM' as const
    })),
    nextPageToken: result.nextPageToken
  };
}

/**
 * Get playlists from a YouTube channel with pagination.
 * Uses playlists.list (1 unit).
 */
export async function getChannelPlaylists(
  channelId: string,
  pageToken?: string
) {
  const result = await ytListChannelPlaylists(channelId, pageToken);

  return {
    items: result.items.map(p => ({
      id: p.id,
      title: p.title,
      thumbnailUrl: p.thumbnailUrl,
      itemCount: p.itemCount
    })),
    nextPageToken: result.nextPageToken
  };
}

/**
 * Get playlist details from YouTube.
 * Uses playlists.list (1 unit).
 */
export async function getPlaylistDetails(playlistId: string) {
  const pl = await getPlaylist(playlistId);

  return {
    title: pl.title,
    thumbnailUrl: pl.thumbnailUrl,
    itemCount: pl.itemCount
  };
}

/**
 * Get videos in a YouTube playlist with pagination.
 * Uses playlistItems.list + videos.list (2 units).
 * Search query filters client-side (playlistItems API has no q param).
 */
export async function getPlaylistVideos(
  playlistId: string,
  pageToken?: string,
  searchQuery?: string
) {
  const result = await ytListPlaylistVideos(playlistId, pageToken);

  let items = result.items.map(v => ({
    id: v.id,
    videoId: v.id,
    title: v.title,
    thumbnailUrl: v.thumbnailUrl
  }));

  // Client-side filtering since playlistItems.list has no q parameter
  if (searchQuery) {
    const query = searchQuery.toLowerCase();
    items = items.filter(v => v.title.toLowerCase().includes(query));
  }

  return {
    items,
    nextPageToken: result.nextPageToken
  };
}
