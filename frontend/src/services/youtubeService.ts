/**
 * YouTube Service
 * Real backend API integration for YouTube search and content preview
 *
 * Backend uses NewPipeExtractor for YouTube content extraction (no API key required).
 * All endpoints return the same data structures, but the backend implementation
 * has been migrated from YouTube Data API v3 to NewPipeExtractor.
 */

import apiClient from './api/client';
import type { AdminSearchChannelResult, AdminSearchPlaylistResult, AdminSearchVideoResult } from '@/types/registry';
import type {
  EnrichedSearchResult,
  SearchPageResponse,
  StreamItemDto,
  PlaylistItemDto,
  PlaylistDetailsDto,
  ChannelDetailsDto,
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
 * Search YouTube for channels, playlists, or videos with pagination support
 * Uses NewPipeExtractor on backend (no API quota limits)
 */
export async function searchYouTube(
  query: string,
  type: 'all' | 'channels' | 'playlists' | 'videos' = 'all',
  pageToken?: string
): Promise<YouTubeSearchResponse> {
  // Use unified search for 'all' - MUCH faster (single backend call) with pagination
  if (type === 'all') {
    const response = await apiClient.get<SearchPageResponse>('/api/admin/youtube/search/all', {
      params: { query, pageToken }
    });

    // Separate by type but preserve mixed order
    const channels: EnrichedSearchResult[] = [];
    const playlists: EnrichedSearchResult[] = [];
    const videos: EnrichedSearchResult[] = [];

    response.data.items.forEach(item => {
      if (item.type === 'channel') channels.push(item);
      else if (item.type === 'playlist') playlists.push(item);
      else if (item.type === 'video') videos.push(item);
    });

    return {
      channels: transformChannelResults(channels),
      playlists: transformPlaylistResults(playlists),
      videos: transformVideoResults(videos),
      nextPageToken: response.data.nextPageToken,
      totalResults: response.data.totalResults
    };
  }

  // Handle single-type responses with typed API calls
  const endpoint = `/api/admin/youtube/search/${type}`;
  const response = await apiClient.get<EnrichedSearchResult[]>(endpoint, {
    params: { query }
  });

  const results = response.data;
  return {
    channels: type === 'channels' ? transformChannelResults(results) : [],
    playlists: type === 'playlists' ? transformPlaylistResults(results) : [],
    videos: type === 'videos' ? transformVideoResults(results) : []
  };
}

/**
 * Get channel details with videos and playlists
 */
export async function getChannelDetails(channelId: string) {
  const [channelResponse, videosResponse, playlistsResponse] = await Promise.all([
    apiClient.get<ChannelDetailsDto>(`/api/admin/youtube/channels/${channelId}`),
    apiClient.get<StreamItemDto[]>(`/api/admin/youtube/channels/${channelId}/videos`),
    apiClient.get<PlaylistItemDto[]>(`/api/admin/youtube/channels/${channelId}/playlists`)
  ]);

  // Map DTOs to EnrichedSearchResult shapes expected by transformers
  const channelAsSearchResult: EnrichedSearchResult = {
    id: channelResponse.data.id || '',
    title: channelResponse.data.name || '',
    thumbnailUrl: channelResponse.data.thumbnailUrl || '',
    description: channelResponse.data.description || '',
    subscriberCount: channelResponse.data.subscriberCount || 0,
    videoCount: channelResponse.data.streamCount || 0,
    type: 'channel'
  };

  const videosAsSearchResults: EnrichedSearchResult[] = videosResponse.data.map(video => ({
    id: video.id || '',
    title: video.name || '',
    thumbnailUrl: video.thumbnailUrl || '',
    description: '',
    channelId: channelResponse.data.id || channelId, // Use parent channel ID
    channelTitle: video.uploaderName || '',
    viewCount: video.viewCount || 0,
    duration: video.duration ? `PT${video.duration}S` : 'PT0S', // Convert to ISO-8601 format for parseDuration
    publishedAt: video.uploadDate || '',
    type: 'video'
  }));

  const playlistsAsSearchResults: EnrichedSearchResult[] = playlistsResponse.data.map(playlist => ({
    id: playlist.id || '',
    title: playlist.name || '',
    thumbnailUrl: playlist.thumbnailUrl || '',
    description: '',
    channelId: channelResponse.data.id || channelId, // Use parent channel ID
    channelTitle: playlist.uploaderName || '',
    itemCount: playlist.streamCount || 0,
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
  const endpoint = itemType === 'channel'
    ? `/api/admin/registry/channels/${itemId}/toggle`
    : `/api/admin/registry/playlists/${itemId}/toggle`;

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

// Additional YouTube functions for exclusions modals (powered by NewPipeExtractor)

/**
 * Get videos from a YouTube channel
 * Note: Backend currently returns flat array without pagination support (no nextPageToken)
 */
export async function getChannelVideos(
  channelId: string,
  pageToken?: string,
  searchQuery?: string
) {
  // Warn if pagination is attempted (backend doesn't support it yet)
  if (pageToken) {
    console.warn(
      '[getChannelVideos] Backend pagination is not supported yet. ' +
      'The pageToken parameter will be ignored and all results will be returned. ' +
      'Please update the backend to support pagination before using this feature.'
    );
  }

  const params: Record<string, string> = {};
  // Do not send pageToken to backend as it's not supported
  if (searchQuery) params.q = searchQuery;

  const response = await apiClient.get<StreamItemDto[]>(
    `/api/admin/youtube/channels/${channelId}/videos`,
    { params }
  );

  const items = response.data;

  return {
    items: items.map(item => ({
      id: item.id || '',
      title: item.name || '',
      thumbnailUrl: item.thumbnailUrl || '',
      publishedAt: item.uploadDate || ''
    })),
    nextPageToken: undefined // Pagination not supported by backend yet
  };
}

/**
 * Get playlists from a YouTube channel
 * Note: Backend currently returns flat array without pagination support (no nextPageToken)
 */
export async function getChannelPlaylists(
  channelId: string,
  pageToken?: string
) {
  // Warn if pagination is attempted (backend doesn't support it yet)
  if (pageToken) {
    console.warn(
      '[getChannelPlaylists] Backend pagination is not supported yet. ' +
      'The pageToken parameter will be ignored and all results will be returned. ' +
      'Please update the backend to support pagination before using this feature.'
    );
  }

  const params: Record<string, string> = {};
  // Do not send pageToken to backend as it's not supported

  const response = await apiClient.get<PlaylistItemDto[]>(
    `/api/admin/youtube/channels/${channelId}/playlists`,
    { params }
  );

  const items = response.data;

  return {
    items: items.map(item => ({
      id: item.id || '',
      title: item.name || '',
      thumbnailUrl: item.thumbnailUrl || '',
      itemCount: item.streamCount || 0
    })),
    nextPageToken: undefined // Pagination not supported by backend yet
  };
}

/**
 * Get playlist details from YouTube
 */
export async function getPlaylistDetails(playlistId: string) {
  const response = await apiClient.get<PlaylistDetailsDto>(
    `/api/admin/youtube/playlists/${playlistId}`
  );

  const data = response.data;

  return {
    title: data.name || '',
    thumbnailUrl: data.thumbnailUrl || '',
    itemCount: data.streamCount || 0
  };
}

/**
 * Get videos in a YouTube playlist
 * Note: Backend currently returns flat array without pagination support (no nextPageToken)
 */
export async function getPlaylistVideos(
  playlistId: string,
  pageToken?: string,
  searchQuery?: string
) {
  // Warn if pagination is attempted (backend doesn't support it yet)
  if (pageToken) {
    console.warn(
      '[getPlaylistVideos] Backend pagination is not supported yet. ' +
      'The pageToken parameter will be ignored and all results will be returned. ' +
      'Please update the backend to support pagination before using this feature.'
    );
  }

  const params: Record<string, string> = {};
  // Do not send pageToken to backend as it's not supported
  if (searchQuery) params.q = searchQuery;

  const response = await apiClient.get<StreamItemDto[]>(
    `/api/admin/youtube/playlists/${playlistId}/videos`,
    { params }
  );

  const items = response.data;

  return {
    items: items.map(item => ({
      id: item.id || '',
      videoId: item.id || '', // StreamItemDto uses 'id' for video ID
      title: item.name || '',
      thumbnailUrl: item.thumbnailUrl || ''
    })),
    nextPageToken: undefined // Pagination not supported by backend yet
  };
}
